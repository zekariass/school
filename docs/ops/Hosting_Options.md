# Hosting Options

Research date: 25 November 2026. Prices change often (Hetzner raised prices several times in 2026); re-check every number on the vendor page before committing. Anything not confirmed from a vendor page or a named source is marked **unverified**. Costs are USD per month, excluding taxes, unless a line says otherwise. Where a vendor quotes EUR, the USD figure uses 1 EUR = 1.18 USD (derived from the Costgoat table, which shows 5.49 EUR = 6.49 USD; the rate itself is unverified).

This document compares hosting; it does not decide the data-residency question. Legal items are open questions for counsel, not legal advice.

## 1. Sizing assumptions

| Item | Staging | Pilot prod |
|---|---|---|
| Users | One developer team plus demo users | About 5 schools, 3,000 students, 250 staff, 3,000 guardians |
| Load | Near zero | Peak about 300 concurrent users |
| Database | Under 1 GB | Under 10 GB in year 1 |
| Media | Negligible | In Cloudflare R2, under 200 GB |
| Backups | Optional | Nightly, at least 14 days retained |
| Availability | None | About 99.5% (about 3.6 h per month); a short maintenance window is acceptable |

Workloads to host:

- Spring Boot 4 / Java 25 API container: about 1 GB RAM realistic minimum, 2 vCPU for the pilot. WebSocket (STOMP) needs an idle timeout above 30 s.
- Next.js 16 web container (Node 26): about 512 MB.
- PostgreSQL 18 (we build against 18 images). A managed Postgres 17 would probably work, but that is **unverified compatibility**; run the test suite against it before relying on it.
- Redis 7.4: cache, rate limits, pub/sub fan-out. Small and non-critical, but needed. Valkey is wire-compatible but compatibility with our usage is **unverified**.
- Flyway migrations run once per deploy, before traffic.
- Cloudflare R2 (decided) and Cloudflare in front (DNS, TLS, CDN, WAF) are listed separately in section 5 and are the same in every option.

## 2. Findings that affect every option

1. **Ethiopian personal data law (open question for counsel).** Law-firm summaries of Proclamation No. 1321/2024 say cross-border transfers need an adequacy finding, safeguards, or explicit consent, that sensitive data needs prior regulator approval, and that Article 22 requires personal data collected locally to be stored on a server or data centre in Ethiopia. Summaries disagree on dates and I could not confirm the penalties from the official text (the PDF I found was garbled). Sources: [Mondaq guide](https://www.mondaq.com/privacy-protection/1766200/ethiopian-personal-data-protection-law-proclamation-13212024-business-compliance-guide), [Black Tree analysis](https://blacktree.nl/2024/07/24/ethiopia-personal-data-protection-data-sovereignty/). If counsel reads Article 22 as applying to us, every option below (all outside Ethiopia, and R2 has no African region) is affected, and in-country colocation becomes the only compliant home for primary data. Raxio, Wingu.Africa and Ethio Telecom all operate Tier III data centres in Addis Ababa ([DCD](https://www.datacenterdynamics.com/en/news/ethio-telecom-opens-new-huawei-built-modular-data-center-in-addis-ababa-ethiopia/), [Raxio](https://www.raxiogroup.com/data-centres/ethiopia/)). Pricing and connectivity for those are **unverified** and not researched here.
2. **Hetzner availability.** On 25 Nov 2026 the Hetzner [cost-optimized page](https://www.hetzner.com/cloud/cost-optimized) marks all CX types (CX23 to CX53) as "currently unavailable". The regular-performance CPX line is listed ([Hetzner CPX page](https://www.hetzner.com/cloud/regular-performance)), and its prices are much higher than the CX prices third parties quote ([Northflank](https://northflank.com/blog/hetzner-cloud-server-price-increases)). Estimates below use CPX.
3. **Internet disruption risk.** Conflict-area shutdowns (Amhara, Tigray) continued into 2026 per [People of Internet](https://peopleofinternet.com/articles/ethiopia-s-endless-blackout-and-the-test-for-south-africa-s-.html) and [Ethiopia Driver](https://ethiopiadriver.com/ethiopia/does-ethiopia-shut-down-the-internet); [Access Now](https://www.accessnow.org/campaign/2026-elections-and-internet-shutdowns-watch/) counts at least 30 shutdowns since 2016 and reported none during the June 2026 election. Nationwide social-media blocks occurred in 2023; I found no confirmed nationwide block in 2026 (secondary sources only). Hosting region cannot mitigate this. Open question: whether the pilot schools are in affected regions, and whether offline-tolerant behaviour in the mobile app is needed.
4. **Cost driver is SMS, not hosting.** See section 8. Hosting for the pilot costs tens to low hundreds of USD per month; international-route SMS OTPs for 3,000 guardians could cost thousands.

## 3. Latency from Addis Ababa (unverified estimates)

No measured mobile-network data was found. The figures below are vendor or academic numbers for fixed enterprise links and **must be treated as unverified**.

| Destination | Estimate | Source and caveat |
|---|---|---|
| Frankfurt (AWS eu-central-1) | 165 to 195 ms | [UT Solutions](https://www.utsolutionsplc.com/insights/enterprise-networking-ethiopia/), enterprise estimate, not mobile |
| Bahrain / Middle East | 95 to 130 ms | Same source |
| Johannesburg | about 88 ms | [Africloud latency table](https://africloud.com/network), method unknown, 52 IPs sampled |
| Cape Town / Azure South Africa North | 220 to 260 ms | UT Solutions |
| Nairobi | no data found | Ethiopia's international traffic mostly leaves via Djibouti; a direct Ethiopia-Kenya terrestrial route is a project, not an established path ([Dawan Africa](https://www.dawan.africa/news/ethio-telecom-djibouti-telecom-and-sudatel-advance-horizon-fiber-corridor)) |
| Europe (2018 study) | 354 ms | Formoso et al. as summarised on ResearchGate; eight years old, likely obsolete |

Takeaways: the sources conflict (Johannesburg 88 ms versus Cape Town 220-260 ms versus Frankfurt 165-195 ms), so an African region is not automatically closer than Europe in network terms. Mobile networks add tens of ms and variance on top. Real-time features (STOMP) tolerate 200 ms; a page that makes 10 sequential API calls does not, so keep the BFF round trips few regardless of region.

Cloudflare lists Addis Ababa (ADD) on its [network page](https://www.cloudflare.com/network/); one third-party mirror says that PoP is not serving traffic, so whether Ethiopian users are served from ADD is **unverified**.

**How the owner measures (one afternoon, about USD 15 of test VMs):**

1. Create throwaway endpoints (any tiny VM running a static file) in Frankfurt or Helsinki, Johannesburg, and Bahrain or Mumbai, or use Cloudflare-proxied hostnames.
2. On an Android phone on Ethio Telecom and another on Safaricom Ethiopia, run `curl -o /dev/null -s -w "%{time_connect} %{time_starttransfer}\n" https://<test host>/` 20 times at different hours (morning, school hours, evening) from a terminal app (Termux), or use a ping app; record median and 95th percentile.
3. Request the real domain behind Cloudflare and read the `cf-ray` response header; the suffix is the serving PoP (for example `-ADD`, `-JNB`, `-NBO`, `-DJI`, `-FRA`). This shows whether Cloudflare serves Ethiopian users locally or from elsewhere.
4. Repeat from a school Wi-Fi/fixed line and from the schools' regions.
5. Decide only if one region is consistently more than about 80 ms better at the 95th percentile.

## 4. R2 location and jurisdiction (do not decide here)

Facts from the [Cloudflare R2 data-location docs](https://developers.cloudflare.com/r2/reference/data-location/):

- R2 has no African region. Location hints are `wnam, enam, weur, eeur, apac, oc` only.
- A **location hint** is best effort, not a guarantee. It is honoured only the first time a bucket name is created.
- A **jurisdiction** (`eu`, `us`, `fedramp`) guarantees storage inside that jurisdiction. It cannot be changed after bucket creation, and it uses a jurisdiction-specific S3 endpoint. Logpush does not work with jurisdiction buckets.

Trade-offs for counsel and the owner:

| Choice | Guarantee | Cost of being wrong |
|---|---|---|
| EU jurisdiction | Objects stay in the EU; helps a GDPR-style story and a stable answer to "where is the data" | Cannot be changed later: a move means creating a new bucket and copying every object (egress is free on R2, so the cost is time and re-pointing presigned URL logic) |
| Default placement with a `weur`/`eeur` hint | Best-effort proximity, no legal guarantee | If a guarantee is later required, same migration |
| Neither is in Africa | Neither satisfies a strict reading of Article 22 | See finding 1 above |

Interaction with API hosting: clients download media with presigned URLs directly from R2 (via Cloudflare), so media bytes never traverse the API host. The API only signs URLs and receives upload-completion calls. An API in the EU talks to R2 on the same continent with low latency; an API in Johannesburg or Cape Town crosses continents for each sign/complete call and for backup uploads, which is acceptable but adds latency to those calls. Region choice for the API therefore matters little for media performance and mostly matters for API round trips and legal posture.

## 5. Costs common to all options

| Item | Price | Source |
|---|---|---|
| R2 storage (Standard) | USD 0.015 per GB-month; first 10 GB-month free | [Cloudflare R2 pricing](https://developers.cloudflare.com/r2/pricing/) |
| R2 operations | Class A USD 4.50 per million; Class B USD 0.36 per million; free tier 1M and 10M per month | Same |
| R2 egress | Free | Same |
| R2 estimate for pilot | 200 GB media plus 50 GB backups: about USD 3.7 per month storage, plus under USD 1 operations: about USD 5 | Derived |
| Cloudflare DNS, TLS, CDN, basic WAF on the Free plan | USD 0 | Cloudflare plan limits **unverified** (check WebSocket idle timeout behaviour; use STOMP heartbeats under 30 s) |
| Domain name | about USD 10 to 15 per year | **Unverified**; registry dependent |

## 6. Options

### Option 1: one VM, Docker Compose, EU (Hetzner Cloud, Helsinki or Falkenstein)

- Regions: FSN1, NBG1 (Germany), HEL1 (Finland) for CPX; also Singapore, US.
- Prices ([Hetzner CPX page](https://www.hetzner.com/cloud/regular-performance), excl. VAT, 20 TB traffic included in EU): CPX22 (2 vCPU, 4 GB, 80 GB) EUR 19.99 FSN / EUR 16.99 HEL; CPX32 (4 vCPU, 8 GB, 160 GB) EUR 35.99 FSN / EUR 30.49 HEL. Whether IPv4 is included in these listed prices is not stated on the page (the cost-optimized page says its listed prices include IPv4; Costgoat shows +EUR 0.50 for IPv4): budget EUR 0.50 extra, **unverified**. Server backup add-on: 20% of server price ([Hetzner docs](https://docs.hetzner.com/cloud/billing/faq/)).
- Managed Postgres/Redis: none. Postgres 18 and Redis 7.4 run as containers, so versions are exactly what we test.
- Backups and PITR: self-managed. `pg_dump` nightly plus optionally WAL archiving (pgBackRest or WAL-G) to R2; Hetzner server backups as a second layer. A restore drill is required before go-live.
- WebSocket: whatever the reverse proxy (Caddy or nginx) and Cloudflare allow; no platform limit.
- Burden: highest. We patch the OS, Docker, Postgres minor versions, monitor disk, and own restores. Whoever is on call is paged for everything.
- Fit with deploy kit: direct (compose over SSH on a VM; GitHub Actions pipelines already exist). Flyway runs as a one-shot compose service before the API starts.
- Exit: `pg_dump`/restore or WAL replication to any managed Postgres; compose files move to any VM or to containers elsewhere.

| Monthly cost (USD) | Staging | Pilot prod |
|---|---|---|
| Compute | CPX22 HEL EUR 16.99 = 20.0 | CPX32 HEL EUR 30.49 = 36.0 (FSN: 42.5) |
| DB, Redis | in the VM | in the VM |
| Backups | R2 dumps, under 1 | Hetzner backups 20% = 7.2; R2 dumps, included in section 5 |
| Bandwidth | included | included (20 TB) |
| Total (excl. R2/Cloudflare) | **about 20** | **about 45** (about 50 with IPv4 and rounding) |

Risks: single point of failure (a VM reboot or host fault means downtime; fits the 99.5% pilot target only with a tested restore path); Hetzner capacity and price volatility in 2026; no Africa region.

### Option 2: PaaS (Fly.io; Render and Railway for comparison)

**Fly.io** ([pricing](https://fly.io/pricing.md), [Managed Postgres docs](https://docs.fly.io/mpg)):

- Regions: Johannesburg (`jnb`) is a compute region with a 1.303 price multiplier and egress of USD 0.12 per GB for Africa and India. Managed Postgres is **not** offered in JNB and staff said no plan for 2026 ([community thread](https://community.fly.io/t/managed-postgres-in-jnb-region/29102)). So a Fly app in JNB would reach a database in Europe, which is a bad pairing; keep app and DB in one European region.
- Managed Postgres: the docs page I could read lists Postgres 16 or 17 only; **Postgres 18 is not confirmed**. Plans: Basic (shared-2x, 1 GB) USD 38; Starter (shared-2x, 2 GB) USD 72; Launch (performance-2x, 8 GB) USD 282; storage USD 0.28 per GB. Backups included; point-in-time restore on Basic and above; Developer plan (USD 4, 256 MB) has daily backups only.
- Redis: not on the pricing page (**unverified**). Run Redis as a small Machine with a volume (USD 0.15 per GB-month).
- Machines: performance-1x 2 GB USD 33 (Ashburn) or USD 43 (JNB); extra RAM USD 6 per GB per month; dedicated IPv4 USD 2. Release commands for Flyway and WebSockets: Fly's proxy generally supports both (idle timeouts **unverified**).
- Burden: low for compute, medium for debugging platform issues. One third-party review reports two new billing lines in 60 days ([bex.co](https://bex.co/blog/2026/09/09/fly-io-billing-changes-three-service-bill)); **unverified**.
- Fit: needs adaptation (`flyctl deploy` from GitHub Actions replaces the compose-over-SSH step; Dockerfiles stay).
- Exit: standard containers and `pg_dump`.

**Render** ([Postgres versions](https://render.com/docs/postgresql-upgrading)): Postgres 13 to 18 supported (18 confirmed). Regions per third parties: three US, Frankfurt, Singapore (region cannot be changed later, **unverified**). Prices could not be loaded from render.com; third-party sites give Key Value from USD 10 and Postgres from USD 6 (tiny) to USD 19-55 for 1-4 GB, with conflicts, so **unverified**. Workspace Pro plan USD 25 per month per third parties.

**Railway**: [docs](https://docs.railway.com/reference/pricing) list usage pricing of USD 10 per GB RAM and USD 20 per vCPU per month, plans USD 5 (Hobby) and USD 20 (Pro); Amsterdam (`europe-west4`) region exists. Its Postgres is a container template rather than a managed service (**unverified**), so it is closer to Option 1 with a nicer UI.

| Monthly cost (USD), Fly.io in a European region | Staging | Pilot prod |
|---|---|---|
| API Machine | about 13 (shared-cpu-2x 2 GB, derived: 4.39 + 1.5 GB x 6) | 33 (performance-1x 2 GB, Ashburn rate; EU rate **unverified**) |
| Web Machine | about 5 (derived) | about 9 (derived) |
| Managed Postgres | 4 to 38 | 72 (Starter) plus 20 GB storage 5.6 |
| Redis Machine plus volume | about 4 (derived) | about 7 (derived) |
| Bandwidth | negligible | about 2 to 10 (egress rate for Europe **unverified**) |
| Total | **about 30 to 65** | **about 130 to 140** |

Cons: Postgres 18 unconfirmed on Fly MPG; no African database region; usage-based bills.

### Option 3: AWS Africa (Cape Town, `af-south-1`) with ECS Fargate + RDS + ElastiCache

- Region: Cape Town. Lightsail is not available in Cape Town ([AWS Lightsail quotas](https://docs.aws.amazon.com/general/latest/gr/lightsail.html), [re:Post](https://repost.aws/questions/QU7V-hLDAMSOeaCfD_RwwDlA/does-amazon-support-lightsail-south-africa-capetown-region-the-copy-snapshot-does-not-shows-south-africa-as-a-region)), so the choice is ECS/Fargate or EC2.
- Managed Postgres: AWS's RDS Proxy and Multi-AZ-cluster support tables list Cape Town with PostgreSQL 18 ([RDS Proxy table](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.RDS_Fea_Regions_DB-eng.Feature.RDSProxy.html)). I found no single page confirming a plain single-instance PG 18 in af-south-1; verify with `aws rds describe-orderable-db-instance-options --engine postgres --engine-version 18 --region af-south-1`.
- Redis: ElastiCache for Valkey `cache.t4g.micro` about USD 0.016 per hour (about USD 12 per month) per [CloudPrice](https://cloudprice.net/aws/elasticache/instances/cache.t4g.micro) (one other source says USD 0.02; **unverified**). Redis 7.4 on ElastiCache itself is not verified.
- Backups: RDS automated backups with point-in-time restore (retention up to 35 days is AWS's documented behaviour from general knowledge, **unverified** here). Multi-AZ is roughly double the instance price.
- WebSocket: supported by ALB; set the idle timeout above 60 s. Burden: medium (AWS manages DB and containers, but VPC, IAM, ALB, task definitions, and cost surprises are ours).
- Fit: poor for the existing kit; needs an ECS deploy action, a Flyway one-off task, and Terraform or similar. Exit: standard containers and `pg_dump`.

| Monthly cost (USD), derived, mostly **unverified** | Staging (smaller, single task each) | Pilot prod (single-AZ DB) |
|---|---|---|
| Fargate API | about 50 (1 vCPU, 2 GB) | about 97 (2 vCPU, 4 GB; vCPU rate USD 0.0546 per hour from [vmprices](https://vmprices.com/serverless/fargate/), memory rate assumed) |
| Fargate web | about 12 | about 24 |
| RDS PostgreSQL | about 30 (smaller instance, price not found) | 60 (`db.t4g.medium`, [Bytebase](https://www.bytebase.com/dbcost/rds/instance/db.t4g.medium/), updated 16 Nov 2026) plus storage about 3; Multi-AZ about 120 |
| ElastiCache | about 12 | about 12 |
| Load balancer | about 25 (assumed) | about 28 (assumed) |
| Data transfer | small | **unverified** per-GB rate in Cape Town |
| Total | **about 130** | **about 225** (about 285 with Multi-AZ) |

Cons: 2 to 3 times the cost of Option 1 for the same pilot; Cape Town to Addis latency not shown to be better than Europe (section 3). Pro: closest major cloud to Africa, managed backups and failover. A single `t4g.large` EC2 instance (about USD 0.067 per hour at an unspecified region per [Vantage](https://instances.vantage.sh/aws/ec2/t4g.large); Cape Town price **unverified**) would make a VM-style variant of Option 1 at about USD 60 to 80.

Other African regions: Google Cloud `africa-south1` (Johannesburg) offers Cloud SQL (Johannesburg added 31 Jan 2024; PG 18 GA 20 Nov 2025; PG 18 in Johannesburg specifically **unverified**) and Memorystore for Valkey (listed from Dec 2025) per [Google release notes](https://docs.cloud.google.com/sql/docs/postgres/release-notes); Cloud Run in that region has pricing SKUs but no official location page was loaded. Azure announced PG 18 as available in all public regions ([Microsoft tech community](https://techcommunity.microsoft.com/blog/adforpostgresql/postgresql-18-now-ga-on-azure-postgres-flexible-server/4469802)); South Africa North itself **unverified**. Neither was priced here. Oracle, Teraco, Liquid and local African providers were not researched.

### Option 4: managed containers in Europe (DigitalOcean App Platform + Managed PostgreSQL + Valkey, Frankfurt)

- Regions: no African region; Frankfurt `fra1` and Amsterdam, London, Bangalore etc. available for databases and App Platform ([DigitalOcean regional availability](https://docs.digitalocean.com/platform/regional-availability/)).
- Postgres: PostgreSQL 18 is available; Advanced Edition defaults to 18; Standard 18 end of life 7 Nov 2030 ([DigitalOcean release notes](https://docs.digitalocean.com/release-notes/postgresql)). Redis: managed Valkey (Redis 7.4 compatibility **unverified**).
- Prices ([managed databases](https://www.digitalocean.com/pricing/managed-databases), [App Platform](https://www.digitalocean.com/pricing/app-platform)): PostgreSQL 1 GiB USD 15.15, 2 GiB USD 30.45, 4 GiB USD 60.90; Valkey 1 GiB USD 15, 2 GiB USD 30; App Platform shared 1 vCPU / 1 GiB USD 12 (150 GiB transfer) and 1 vCPU / 2 GiB USD 25 (200 GiB), overage USD 0.02 per GiB; dedicated container prices not shown. One secondary source says new Standard-edition clusters above 4 GiB RAM or with standby nodes are restricted from 30 Nov 2026 (**unverified**).
- Backups: the pricing pages I could load do not state frequency, retention or PITR; **unverified**. We would still add our own nightly dump to R2 to guarantee 14 days.
- WebSocket and release step: App Platform supports pre-deploy jobs and WebSockets per general knowledge, **unverified** (not stated on the pricing page). Burden: low; DO manages the DB and patching.
- Fit: needs adaptation (App Platform spec plus a GitHub Actions deploy; or use a Droplet with the existing kit and only the managed DB). A hybrid that keeps compose on a Droplet and uses managed Postgres is the lowest-effort variant.

| Monthly cost (USD) | Staging | Pilot prod |
|---|---|---|
| API container | 12 (1 vCPU, 1 GiB; tight for JVM) | 25 (1 vCPU, 2 GiB; fewer than the 2 vCPU target, dedicated price not found) |
| Web container | 10 to 12 | 12 |
| PostgreSQL | 15.15 | 30.45 (2 GiB) to 60.90 (4 GiB) |
| Valkey | 15 | 15 |
| Bandwidth | within allowance | within allowance (about 350 GiB included across containers) |
| Total | **about 52 to 54** | **about 83 to 113** |

Cons: managed DB is an extra cost; CPU for the API is the weak point on shared plans; App Platform has no African region.

### Option 5: VM in Africa (Johannesburg) versus the EU VM

Same shape as Option 1 but in an African region. Candidates: Vultr Johannesburg (2 vCPU / 4 GB regular plan about USD 20 elsewhere, **Johannesburg price unverified**, [Better Stack review](https://betterstack.com/community/guides/web-servers/vultr-review/)); Fly `jnb` machines (see Option 2); AWS EC2 Cape Town. Nairobi: no provider was researched. Benefits: possible but unproven latency gain (section 3) and a stronger "African hosting" narrative; the legal benefit is **unverified** because South Africa is still outside Ethiopia. Costs: typically more expensive than Hetzner (**unverified** per provider), smaller provider ecosystem, same self-managed burden. Not recommended unless the owner's measurements show a clear latency win.

Variant 5b: in-country colocation (Raxio, Wingu.Africa, Ethio Telecom). Only relevant if counsel requires local storage. Cost, bandwidth, international transit to Cloudflare, power reliability and the ability to run containers there are all **unverified**; this needs a direct vendor conversation and a separate plan.

## 7. Cost summary

| Option | Staging | Pilot prod | Managed Postgres 18 | Africa region | Fit with deploy kit |
|---|---|---|---|---|---|
| 1. Hetzner VM (HEL) + compose | about 20 | about 45 | No (container, PG 18 exact) | No | Direct |
| 2. Fly.io (EU region) | about 30-65 | about 130-140 | Unconfirmed (docs list 16, 17) | Compute only (JNB) | Adapt |
| 3. AWS af-south-1 (Fargate + RDS) | about 130 | about 225-285 | Likely (indirect) | Yes | Large rewrite |
| 4. DigitalOcean (Frankfurt) | about 52-54 | about 83-113 | Yes | No | Adapt, or hybrid with Droplet |
| 5. Johannesburg VM | not priced | not priced (about 20 per 2 vCPU / 4 GB VM, unverified) | No | Yes | Direct |

R2, Cloudflare Free plan and domain are additional and identical: about USD 5 per month for R2 at pilot scale, domain about USD 1 per month.

## 8. Recommendation

**Staging:** one Hetzner Cloud CPX22 (Helsinki), Docker Compose with Postgres 18 and Redis 7.4 containers, nightly dumps to an R2 backup bucket. About USD 20 per month. Use the same compose files and deploy pipeline as production so the kit is exercised constantly.

**Pilot prod:** one Hetzner CPX32 (4 vCPU, 8 GB) in Helsinki or Falkenstein, same compose stack, Hetzner server backups on, nightly `pg_dump` (plus WAL archiving if the owner wants a recovery point under 24 hours) to R2 with 14-day or longer retention, and a documented restore drill before launch. About USD 45 per month, excluding R2. Reasons: cheapest option by a factor of 2 to 5; exact Postgres 18 and Redis 7.4 versions with no compatibility risk; direct fit with the existing deploy kit; Europe is as likely as any African region to give acceptable latency (section 3) and sits on the same continent as R2.

Caveats the owner must accept: single point of failure (acceptable for a pilot at 99.5%, a short maintenance window is allowed); self-managed operations; hosting outside Ethiopia pending counsel's reading of the data law (section 2, finding 1). If counsel requires local storage, this recommendation is void and a separate in-country plan is needed. If the team will not run restore drills or on-call for the database, choose Option 4 (DigitalOcean Frankfurt with managed PostgreSQL 18, about USD 83 to 113) instead.

**Migration path:**

1. Start: one VM per environment, compose over SSH, backups to R2.
2. Move the database to managed Postgres (DigitalOcean, or AWS RDS if the owner wants an African region) when any one of these holds: the database exceeds about 50 GB; a recovery point under 15 minutes is needed; nobody on the team can restore from backup in under an hour; availability target rises above 99.5%. Change in the kit: remove the Postgres service from compose, put the connection string and TLS settings in secrets, run Flyway against the managed instance, and keep `pg_dump` to R2 as an independent copy.
3. Go multi-instance (two API containers behind a load balancer) when CPU stays above 60% at peak or zero-downtime deploys are required. Prerequisites: Redis already carries pub/sub for WebSocket fan-out, so API instances can scale horizontally; sessions are token-based. Change in the kit: a second VM or a container platform, a load balancer (Cloudflare Load Balancing or the provider's), a deploy that rolls instances one at a time, and Flyway run once from a single job.
4. Region move: only after the owner's latency measurements and counsel's decision. Since R2 is region-independent, moving the API is a DNS change plus a database restore.

## 9. What the owner must decide or provide

| Item | Detail | Indicative cost (USD) |
|---|---|---|
| Hosting account | Hetzner Cloud (or the chosen provider), payment method, project per environment | see section 6 |
| Domain name | Registered, with DNS at Cloudflare | about 10-15 per year (**unverified**) |
| Region and jurisdiction decision | After counsel: EU jurisdiction versus default placement for R2; hosting location; in-country storage question | counsel fees **unverified** |
| Cloudflare account | Free plan to start; enable R2 with a payment method; create separate buckets for media and backups; create API tokens | R2 about 5 per month at pilot scale |
| GitHub | Environments `staging` and `production` with required reviewers for production; secrets for SSH key, host, registry access, R2 credentials, database password, JWT keys, SMS and email keys | free |
| Expo / EAS | Expo account; Free plan has 15 Android and 15 iOS builds per month; Starter USD 19 per month; Production USD 199 per month ([third-party summary](https://checkthat.ai/brands/expo/pricing), confirm on expo.dev/pricing) | 0 to 19 per month |
| Apple Developer Program | Annual; fee waiver possible for nonprofits and accredited educational institutions ([Apple](https://developer.apple.com/programs/whats-included/)) | 99 per year |
| Google Play Console | One-time fee; organization accounts need business documents and probably a D-U-N-S number ([Google Help](https://support.google.com/googleplay/android-developer/answer/6112435)) | 25 once |
| Email provider (SMTP) | Verified sending domain with SPF, DKIM, DMARC. [Resend](https://resend.com/pricing): free 3,000 per month (100 per day), USD 20 for 50,000. [Postmark](https://postmarkapp.com/pricing): free 100 per month, USD 15 for 10,000. [Amazon SES](https://aws.amazon.com/ses/pricing/): USD 0.10 per 1,000 | 0 to 20 per month |
| Error tracking | [Sentry](https://sentry.io/pricing/): Developer free for 1 user (5k errors); Team USD 29 per month (26 annual), unlimited users, 50k errors. GlitchTip hosted or self-hosted is cheaper but pricing was not verified | 0 to 29 per month |
| Uptime monitoring | [UptimeRobot](https://uptimerobot.com/pricing/) free: 50 monitors, 5-minute interval, commercial use allowed; [Better Stack](https://betterstack.com/pricing) free: 10 monitors | 0 |
| Log shipping | [Grafana Cloud](https://grafana.com/pricing/) free: 50 GB logs, 10k metric series, 14-day retention, 3 users; [Better Stack](https://betterstack.com/pricing) free: 3 GB logs with 3-day retention. Do not ship personal data or login codes to third parties | 0 (Pro from 19) |
| Push notifications | Expo push service via the existing `PushSender` SPI; pricing **unverified** | likely 0 |

### SMS for Ethiopia (research, all **unverified** for the owner's volume and sender-ID status)

- Twilio says sender ID registration is required for Ethio Telecom and unregistered traffic may be filtered ([Twilio Ethiopia guidelines](https://www.twilio.com/en-us/guidelines/et/sms)). Safaricom Ethiopia rules were found only in a weak secondary source ([sent.dm](https://www.sent.dm/en/resources/sms-compliance/ethiopia-sms-guide)).
- Per-SMS prices to Ethiopia: Plivo USD 0.3352 ([Plivo](https://www.plivo.com/sms/pricing/et/)); Twilio about USD 0.34 ([sent.dm comparison](https://www.sent.dm/en/resources/sms-pricing/ethiopia-sms-pricing), 4 Sept 2026); Unimatrix USD 0.1547 ([Unimatrix](https://www.unimtx.com/sms/et)); local providers 0.10 to 0.45 ETB, for example 251SMS 0.45 ETB falling to 0.23 ETB at 100,000 messages excluding 15% VAT ([251SMS](https://251sms.com/)), SMSEthiopia 0.10 to 0.45 ETB, FalconVAS from 0.163 ETB. Exchange rate not verified, but local routes are far cheaper than international ones.
- Africa's Talking lists Ethio Telecom at ETB 9.50 on its [bulk SMS page](https://africastalking.com/sms/bulksms); the unit is unclear, and its sender-ID fee statements conflict (free versus USD 35). Ask the vendor. Infobip and Vonage Ethiopia prices were not found.
- Illustration (assumption: 3,000 guardians each receive 4 SMS per month, 12,000 SMS): international routes cost about USD 1,900 (Unimatrix) to USD 4,000 (Plivo) per month. Local routes at 0.23 to 0.45 ETB cost 2,800 to 5,400 ETB per month. Recommendation: use push, email and in-app notices first; reserve SMS for OTP and critical alerts; get written quotes and sender-ID lead times (sources range from 2 days to 10 business days) before launch; a local aggregator and Africa's Talking are the first two to quote.

## 10. Unverified claims

- Every latency figure in section 3, including the status of the Cloudflare Addis Ababa PoP.
- EUR to USD rate; IPv4 inclusion in Hetzner CPX prices; Hetzner third-party reports of repeated 2026 price rises; Hetzner CX return date.
- Postgres 18 on Fly Managed Postgres, in single-instance RDS in af-south-1, in Cloud SQL `africa-south1`, and in Azure South Africa North; compatibility of managed Postgres 17 with our migrations; compatibility of Valkey (DigitalOcean, ElastiCache) with Redis 7.4 features we use.
- All derived Fly.io estimates (RAM rates in a European region, Redis machine, egress outside Africa), Render prices, Railway managed-Postgres status, AWS memory and load balancer rates, AWS data transfer, RDS backup retention, Google and Azure prices, Vultr Johannesburg price.
- DigitalOcean backup retention and PITR, App Platform WebSocket and pre-deploy job support, the 30 Nov 2026 Standard-edition restriction.
- Cloudflare Free-plan WebSocket idle limits; Expo plan and build allowances (third-party sources); domain price.
- Ethiopian law: Article 22 text and its application to us, penalty amounts, whether in-country storage is required, ECA adequacy rulings, and sender-ID regulatory rules. Counsel to review.
- Ethio Telecom, Safaricom Ethiopia and local data-centre colocation pricing and connectivity.
- All SMS aggregator prices, sender-ID fees and lead times.
