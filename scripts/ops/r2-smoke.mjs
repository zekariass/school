#!/usr/bin/env node
// Manual smoke test of an S3-compatible bucket for the media pipeline: the nine steps of
// docs/research/R2_Compatibility.md ("Manual smoke test against a real staging bucket").
//
//   SMOKE_ENDPOINT=https://<account>.r2.cloudflarestorage.com SMOKE_BUCKET=school-staging-media \
//   SMOKE_ACCESS_KEY=... SMOKE_SECRET_KEY=... SMOKE_WEB_ORIGIN=https://staging.example.org \
//   SMOKE_OTHER_BUCKET=some-other-bucket node scripts/ops/r2-smoke.mjs
//
// Locally against SeaweedFS (npm run infra:up; steps marked [R2 only] are informational there):
//   SMOKE_ENDPOINT=http://localhost:8343 SMOKE_REGION=us-east-1 SMOKE_BUCKET=school-media-dev \
//   SMOKE_ACCESS_KEY=school-dev SMOKE_SECRET_KEY=school-dev-secret SMOKE_WEB_ORIGIN=http://localhost:3100 \
//   node scripts/ops/r2-smoke.mjs
//
// Environment (credentials come only from the environment, nothing is stored):
//   SMOKE_ENDPOINT        S3 API endpoint (R2: https://<account>[.eu].r2.cloudflarestorage.com)   required
//   SMOKE_BUCKET          the bucket under test                                                    required
//   SMOKE_ACCESS_KEY      runtime token (Object Read & Write for that one bucket)                  required
//   SMOKE_SECRET_KEY                                                                               required
//   SMOKE_REGION          signing region (default "auto"; SeaweedFS: us-east-1)
//   SMOKE_WEB_ORIGIN      browser origin whose CORS rule is tested (step 6; skipped when unset)
//   SMOKE_OTHER_BUCKET    a bucket the runtime token must NOT reach (step 9; skipped when unset)
//   SMOKE_ADMIN_ACCESS_KEY / SMOKE_ADMIN_SECRET_KEY  token allowed to change bucket lifecycle (step 8;
//                         defaults to the runtime token, which R2 will refuse: use an Admin token)
//   SMOKE_PREFIX          key prefix for the test objects (default smoke-test/<timestamp>/)
//
// Results: PASS / FAIL (exit code 1) / INFO (a recorded observation, e.g. the open Content-Length question)
// / SKIP. Test objects are deleted at the end. The script never prints credentials.

import crypto from 'node:crypto'
import http from 'node:http'
import https from 'node:https'

const env = process.env
for (const name of ['SMOKE_ENDPOINT', 'SMOKE_BUCKET', 'SMOKE_ACCESS_KEY', 'SMOKE_SECRET_KEY']) {
  if (!env[name]) {
    console.error(`Missing ${name}. See the header of scripts/ops/r2-smoke.mjs.`)
    process.exit(2)
  }
}
const endpoint = new URL(env.SMOKE_ENDPOINT)
const bucket = env.SMOKE_BUCKET
const region = env.SMOKE_REGION || 'auto'
const creds = { accessKey: env.SMOKE_ACCESS_KEY, secretKey: env.SMOKE_SECRET_KEY }
const adminCreds = env.SMOKE_ADMIN_ACCESS_KEY
  ? { accessKey: env.SMOKE_ADMIN_ACCESS_KEY, secretKey: env.SMOKE_ADMIN_SECRET_KEY }
  : creds
const prefix = env.SMOKE_PREFIX || `smoke-test/${new Date().toISOString().replace(/[-:.TZ]/g, '')}/`
const isR2 = endpoint.hostname.endsWith('r2.cloudflarestorage.com')

let failures = 0
const created = []

// ---------------------------------------------------------------- SigV4

const sha256 = (data) => crypto.createHash('sha256').update(data).digest('hex')
const hmac = (key, data) => crypto.createHmac('sha256', key).update(data).digest()
const enc = (s) => encodeURIComponent(s).replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase())
const amzDate = (d = new Date()) => d.toISOString().replace(/[-:]|\.\d{3}/g, '')

function signingKey(secret, date) {
  return hmac(hmac(hmac(hmac('AWS4' + secret, date.slice(0, 8)), region), 's3'), 'aws4_request')
}

function canonicalPath(bucketName, key) {
  const segments = [bucketName, ...(key ? key.split('/') : [])]
  return '/' + segments.map(enc).join('/')
}

function hostHeader() {
  return endpoint.host // includes a non-default port, as clients send it
}

/** A presigned URL (query-string signature), like the backend's presigner. */
function presign(method, bucketName, key, { expires = 300, signed = {}, query = {} } = {}) {
  const now = amzDate()
  const headers = { host: hostHeader(), ...signed }
  const names = Object.keys(headers).sort()
  const q = {
    'X-Amz-Algorithm': 'AWS4-HMAC-SHA256',
    'X-Amz-Credential': `${creds.accessKey}/${now.slice(0, 8)}/${region}/s3/aws4_request`,
    'X-Amz-Date': now,
    'X-Amz-Expires': String(expires),
    'X-Amz-SignedHeaders': names.join(';'),
    ...query,
  }
  const canonicalQuery = Object.keys(q)
    .sort()
    .map((k) => `${enc(k)}=${enc(q[k])}`)
    .join('&')
  const path = canonicalPath(bucketName, key)
  const canonical = [
    method,
    path,
    canonicalQuery,
    names.map((n) => `${n}:${String(headers[n]).trim()}\n`).join(''),
    names.join(';'),
    'UNSIGNED-PAYLOAD',
  ].join('\n')
  const scope = `${now.slice(0, 8)}/${region}/s3/aws4_request`
  const toSign = ['AWS4-HMAC-SHA256', now, scope, sha256(canonical)].join('\n')
  const signature = crypto.createHmac('sha256', signingKey(creds.secretKey, now)).update(toSign).digest('hex')
  return `${endpoint.protocol}//${endpoint.host}${path}?${canonicalQuery}&X-Amz-Signature=${signature}`
}

/** Sends a request signed in the Authorization header (server-side calls). */
function signedRequest(method, bucketName, key, { body = Buffer.alloc(0), headers = {}, query = '', auth = creds } = {}) {
  const now = amzDate()
  const payloadHash = sha256(body)
  const all = {
    host: hostHeader(),
    'x-amz-content-sha256': payloadHash,
    'x-amz-date': now,
    ...Object.fromEntries(Object.entries(headers).map(([k, v]) => [k.toLowerCase(), String(v)])),
  }
  if (body.length > 0 || method === 'PUT') all['content-length'] = String(body.length)
  const names = Object.keys(all).sort()
  const path = canonicalPath(bucketName, key)
  const canonical = [
    method,
    path,
    query,
    names.map((n) => `${n}:${all[n].trim()}\n`).join(''),
    names.join(';'),
    payloadHash,
  ].join('\n')
  const scope = `${now.slice(0, 8)}/${region}/s3/aws4_request`
  const toSign = ['AWS4-HMAC-SHA256', now, scope, sha256(canonical)].join('\n')
  const signature = crypto.createHmac('sha256', signingKey(auth.secretKey, now)).update(toSign).digest('hex')
  all.authorization = `AWS4-HMAC-SHA256 Credential=${auth.accessKey}/${scope}, SignedHeaders=${names.join(';')}, Signature=${signature}`
  const url = `${endpoint.protocol}//${endpoint.host}${path}${query ? '?' + query : ''}`
  return send(method, url, all, body)
}

function send(method, url, headers, body = Buffer.alloc(0)) {
  const target = new URL(url)
  const client = target.protocol === 'https:' ? https : http
  return new Promise((resolve, reject) => {
    const req = client.request(
      target,
      { method, headers: { ...headers, ...(body.length || method === 'PUT' ? { 'content-length': String(body.length) } : {}) } },
      (res) => {
        const chunks = []
        res.on('data', (c) => chunks.push(c))
        res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }))
      },
    )
    req.on('error', reject)
    req.setTimeout(30_000, () => req.destroy(new Error('timeout')))
    req.end(body)
  })
}

// --------------------------------------------------------------- reporting

function report(level, step, message) {
  if (level === 'FAIL') failures++
  console.log(`${level.padEnd(4)}  ${step}  ${message}`)
}

const pass = (step, message) => report('PASS', step, message)
const fail = (step, message) => report('FAIL', step, message)
const info = (step, message) => report('INFO', step, message)
const skip = (step, message) => report('SKIP', step, message)

async function putPresigned(key, body, contentType, { declaredLength = body.length, origin } = {}) {
  const url = presign('PUT', bucket, key, {
    signed: { 'content-length': String(declaredLength), 'content-type': contentType },
  })
  return send('PUT', url, { 'content-type': contentType, ...(origin ? { origin } : {}) }, body)
}

function jpeg(size) {
  // JFIF magic followed by filler: enough for a storage-level test (nothing here is decoded).
  const buf = Buffer.alloc(size, 0x41)
  Buffer.from([0xff, 0xd8, 0xff, 0xe0]).copy(buf)
  return buf
}

// ------------------------------------------------------------------- steps

async function main() {
  console.log(`Target: ${endpoint.origin}  bucket: ${bucket}  region: ${region}  prefix: ${prefix}`)
  console.log(isR2 ? 'Detected Cloudflare R2.' : 'Not an R2 endpoint: steps marked [R2 only] are informational.')

  const key = `${prefix}exact.jpg`
  const type = 'image/jpeg'
  const body = jpeg(8192)

  // 1. presigned PUT, type and length signed, exact size
  const exact = await putPresigned(key, body, type)
  if (exact.status === 200) {
    pass('1', 'presigned PUT with signed Content-Type and Content-Length, exact size: 200')
    created.push(key)
  } else {
    fail('1', `expected 200, got ${exact.status}: ${exact.body.toString('utf8').slice(0, 200)}`)
  }

  // 2. wrong Content-Type
  const wrongTypeKey = `${prefix}wrong-type.jpg`
  const wrongTypeUrl = presign('PUT', bucket, wrongTypeKey, {
    signed: { 'content-length': String(body.length), 'content-type': type },
  })
  const wrongType = await send('PUT', wrongTypeUrl, { 'content-type': 'text/html' }, body)
  if (wrongType.status === 403) pass('2', 'upload with a different Content-Type: 403')
  else fail('2', `expected 403, got ${wrongType.status}`)
  created.push(wrongTypeKey)

  // 3. larger and smaller than the signed Content-Length (the open question on R2)
  for (const [label, actual] of [['larger', body.length + 512], ['smaller', body.length - 512]]) {
    const k = `${prefix}${label}.jpg`
    const url = presign('PUT', bucket, k, { signed: { 'content-length': String(body.length), 'content-type': type } })
    const res = await send('PUT', url, { 'content-type': type }, jpeg(actual))
    created.push(k)
    info(
      '3',
      `${label} body than signed (${actual} vs ${body.length} bytes): ${res.status} ` +
        (res.status === 200 ? '(ACCEPTED: the size is NOT enforced by the store; `complete` must check it)' : '(rejected)'),
    )
  }

  // 4. HeadObject, ranged GET, server-side PUT without chunked encoding
  const head = await signedRequest('HEAD', bucket, key)
  if (head.status === 200 && Number(head.headers['content-length']) === body.length) {
    pass('4a', `HeadObject: 200, Content-Length ${head.headers['content-length']}`)
  } else {
    fail('4a', `HeadObject returned ${head.status}, length ${head.headers['content-length']}`)
  }
  const range = await signedRequest('GET', bucket, key, { headers: { Range: 'bytes=0-4095' } })
  if (range.status === 206 && range.body.length === 4096) pass('4b', 'ranged GET bytes=0-4095: 206 with 4096 bytes')
  else fail('4b', `ranged GET returned ${range.status} with ${range.body.length} bytes`)
  const variantKey = `${prefix}variant-thumb.jpg`
  const variant = await signedRequest('PUT', bucket, variantKey, { body: jpeg(2048), headers: { 'content-type': type } })
  if (variant.status === 200) {
    pass('4c', 'server-side PutObject (single signed PUT, no aws-chunked): 200')
    created.push(variantKey)
  } else {
    fail('4c', `server-side PutObject returned ${variant.status}: ${variant.body.toString('utf8').slice(0, 200)}`)
  }
  info(
    '4c',
    'The SDK flag chunkedEncodingEnabled(false) in S3ObjectStorage is verified by a real upload through the backend ' +
      '(variants appear after POST /media/{id}/complete); this script only proves the plain signed PUT.',
  )

  // 5. presigned GET with response-content-disposition
  const getUrl = presign('GET', bucket, key, { query: { 'response-content-disposition': 'attachment; filename="smoke.jpg"' } })
  const got = await send('GET', getUrl, {})
  const disposition = got.headers['content-disposition']
  if (got.status === 200 && disposition && disposition.includes('smoke.jpg')) {
    pass('5', `presigned GET returns the overridden Content-Disposition (${disposition})`)
  } else {
    fail('5', `presigned GET status ${got.status}, content-disposition: ${disposition}`)
  }

  // 6. CORS preflight and ETag exposure for the web origin
  if (env.SMOKE_WEB_ORIGIN) {
    const origin = env.SMOKE_WEB_ORIGIN
    const k = `${prefix}cors.jpg`
    const url = presign('PUT', bucket, k, { signed: { 'content-length': '1024', 'content-type': type } })
    const pre = await send('OPTIONS', url, {
      origin,
      'access-control-request-method': 'PUT',
      'access-control-request-headers': 'content-type',
    })
    const allowOrigin = pre.headers['access-control-allow-origin']
    if (pre.status >= 200 && pre.status < 300 && (allowOrigin === origin || allowOrigin === '*')) {
      pass('6a', `preflight from ${origin}: ${pre.status}, allow-origin ${allowOrigin}`)
    } else {
      fail('6a', `preflight from ${origin}: ${pre.status}, allow-origin ${allowOrigin}`)
    }
    const put = await send('PUT', url, { 'content-type': type, origin }, jpeg(1024))
    created.push(k)
    const exposed = String(put.headers['access-control-expose-headers'] || '')
    if (put.status === 200 && /etag/i.test(exposed)) pass('6b', 'PUT with Origin: 200 and ETag is exposed to the browser')
    else fail('6b', `PUT status ${put.status}, access-control-expose-headers: "${exposed}" (a CORS rule must expose ETag)`)
    console.log('      (a real browser upload from the staging web app is still worth one manual try)')
  } else {
    skip('6', 'SMOKE_WEB_ORIGIN not set')
  }

  // 7. delete, then delete again: no error
  const toDelete = created.filter((k) => k.endsWith('exact.jpg'))[0] || key
  const first = await signedRequest('DELETE', bucket, toDelete)
  const second = await signedRequest('DELETE', bucket, toDelete)
  if (first.status === 204 && second.status === 204) pass('7', 'DeleteObject twice: 204 both times')
  else fail('7', `DeleteObject returned ${first.status} then ${second.status}`)

  // 8. lifecycle rule on a test prefix, read it back [R2 only]
  await lifecycleStep()

  // 9. the runtime token must not reach another bucket
  if (env.SMOKE_OTHER_BUCKET) {
    const res = await signedRequest('PUT', env.SMOKE_OTHER_BUCKET, `${prefix}should-not-exist.txt`, {
      body: Buffer.from('x'),
      headers: { 'content-type': 'text/plain' },
    })
    if (res.status === 403) pass('9', `runtime token is refused on bucket ${env.SMOKE_OTHER_BUCKET}: 403`)
    else {
      const message = `expected 403 on another bucket, got ${res.status} (the token is not scoped to one bucket!)`
      if (isR2) fail('9', message)
      else info('9 [R2 only]', `${message}; development credentials are not bucket-scoped, so this is expected here`)
      if (res.status === 200) await signedRequest('DELETE', env.SMOKE_OTHER_BUCKET, `${prefix}should-not-exist.txt`)
    }
  } else {
    skip('9', 'SMOKE_OTHER_BUCKET not set')
  }
}

async function lifecycleStep() {
  const label = isR2 ? '8' : '8 [R2 only]'
  const rulePrefix = `${prefix}lifecycle/`
  const xml =
    '<?xml version="1.0" encoding="UTF-8"?><LifecycleConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">' +
    `<Rule><ID>smoke-test-expire</ID><Status>Enabled</Status><Filter><Prefix>${rulePrefix}</Prefix></Filter>` +
    '<Expiration><Days>1</Days></Expiration></Rule></LifecycleConfiguration>'
  const body = Buffer.from(xml)
  const md5 = crypto.createHash('md5').update(body).digest('base64')
  try {
    // Replaces the bucket's lifecycle configuration: only run this against a bucket without rules you need.
    const existing = await signedRequest('GET', bucket, '', { query: 'lifecycle=', auth: adminCreds })
    if (existing.status === 200 && existing.body.toString('utf8').includes('<Rule>') && !env.SMOKE_OVERWRITE_LIFECYCLE) {
      skip(label, 'the bucket already has lifecycle rules; set SMOKE_OVERWRITE_LIFECYCLE=1 to replace them for the test')
      return
    }
    const put = await signedRequest('PUT', bucket, '', {
      query: 'lifecycle=',
      body,
      headers: { 'content-type': 'application/xml', 'content-md5': md5 },
      auth: adminCreds,
    })
    if (put.status !== 200) {
      if (isR2) fail(label, `PutBucketLifecycleConfiguration returned ${put.status} (needs an Admin token): ${put.body.toString('utf8').slice(0, 200)}`)
      else info(label, `lifecycle not supported here (${put.status}); expected outside R2`)
      return
    }
    const read = await signedRequest('GET', bucket, '', { query: 'lifecycle=', auth: adminCreds })
    if (read.status === 200 && read.body.toString('utf8').includes('smoke-test-expire')) {
      pass(label, 'lifecycle rule written and read back')
    } else {
      fail(label, `GetBucketLifecycleConfiguration returned ${read.status} without the rule`)
    }
    // Remove it again so the staging rule set (stale PENDING_UPLOAD objects) can be applied cleanly.
    await signedRequest('DELETE', bucket, '', { query: 'lifecycle=', auth: adminCreds })
    console.log('      (test rule removed; apply the real PENDING_UPLOAD expiry rule from docs/ops/Runbook.md afterwards)')
  } catch (error) {
    fail(label, `request failed: ${error.message}`)
  }
}

async function cleanup() {
  for (const key of new Set(created)) {
    try {
      await signedRequest('DELETE', bucket, key)
    } catch {
      // best effort
    }
  }
}

try {
  await main()
} catch (error) {
  failures++
  console.error(`ERROR ${error.message}`)
} finally {
  await cleanup()
}
console.log(failures === 0 ? '\nSmoke test finished without failures.' : `\n${failures} step(s) FAILED.`)
process.exit(failures === 0 ? 0 : 1)
