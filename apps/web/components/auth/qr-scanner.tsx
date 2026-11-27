"use client";

import { CameraIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";
import { classifyLoginScan } from "@school/validation/login-code";
import { Button } from "@/components/ui/button";
import { SCAN_COOLDOWN_MS, cameraProblemOf, frameDue, scanSize, type CameraProblem } from "@/lib/auth/student-code";
import { cn } from "@/lib/utils";

type Phase = "idle" | "starting" | "scanning" | CameraProblem;
type Seen = "looking" | "found" | "notACode";

/**
 * Scans the QR code a teacher printed with the device's camera. The camera is asked for only when the child presses the
 * button (a user gesture, never on page load) and is stopped when the page is left or a code was found. Frames are decoded in
 * the browser by jsQR, loaded only here (a dynamic import: the sign-in page costs nothing until a child chooses the camera);
 * nothing is recorded, uploaded or kept, and a picture of the code never leaves the page. Only a bare code (see
 * `classifyLoginScan`) is passed on; anything else a camera sees is ignored and said so.
 */
export function QrScanner({ onCode, paused }: { onCode: (code: string) => void; paused?: boolean }) {
  const t = useTranslations("auth.studentCode.scan");
  const [phase, setPhase] = useState<Phase>("idle");
  const [seen, setSeen] = useState<Seen>("looking");
  const video = useRef<HTMLVideoElement>(null);
  const stream = useRef<MediaStream | null>(null);
  const onCodeRef = useRef(onCode);
  const pausedRef = useRef(!!paused);
  useEffect(() => {
    onCodeRef.current = onCode;
    pausedRef.current = !!paused;
  });

  function stop() {
    stream.current?.getTracks().forEach((track) => track.stop());
    stream.current = null;
    if (video.current) video.current.srcObject = null;
  }
  // Always give the camera back when the page is left.
  useEffect(() => stop, []);

  async function start() {
    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
      setPhase("unavailable");
      return;
    }
    setPhase("starting");
    try {
      const media = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: "environment" } }, audio: false });
      stream.current = media;
      setPhase("scanning");
    } catch (error) {
      setPhase(cameraProblemOf(error));
    }
  }

  // Once the video element exists (phase "scanning"), play the stream and read frames.
  useEffect(() => {
    if (phase !== "scanning") return;
    const element = video.current;
    const media = stream.current;
    if (!element || !media) return;
    let cancelled = false;
    let frame = 0;
    let last = 0;
    let waitUntil = 0;
    let tried: string | null = null;
    const canvas = document.createElement("canvas");
    const context = canvas.getContext("2d", { willReadFrequently: true });
    element.srcObject = media;
    element.setAttribute("playsinline", "true");
    void element.play().catch(() => undefined);

    void import("jsqr").then(({ default: jsQR }) => {
      if (cancelled || !context) return;
      const tick = (time: number) => {
        if (cancelled) return;
        frame = requestAnimationFrame(tick);
        if (pausedRef.current || time < waitUntil || !frameDue(time, last)) return;
        last = time;
        if (element.readyState < element.HAVE_CURRENT_DATA) return;
        const size = scanSize(element.videoWidth, element.videoHeight);
        if (size.width === 0) return;
        canvas.width = size.width;
        canvas.height = size.height;
        context.drawImage(element, 0, 0, size.width, size.height);
        const image = context.getImageData(0, 0, size.width, size.height);
        const result = jsQR(image.data, size.width, size.height, { inversionAttempts: "dontInvert" });
        if (!result) {
          // Nothing in view: the same code may be tried again once it has been taken away and shown again.
          tried = null;
          setSeen("looking");
          return;
        }
        const scan = classifyLoginScan(result.data);
        // A code that is not a class code (a link, a Wi-Fi code) is ignored for a moment; so is a code that was just tried.
        waitUntil = time + SCAN_COOLDOWN_MS;
        if (scan.kind === "unknown") {
          setSeen("notACode");
          return;
        }
        // A code held still in front of the camera is sent once, not over and over (each wrong try counts against the limit).
        if (scan.code === tried) return;
        tried = scan.code;
        setSeen("found");
        onCodeRef.current(scan.code);
      };
      frame = requestAnimationFrame(tick);
    });

    return () => {
      cancelled = true;
      cancelAnimationFrame(frame);
    };
  }, [phase]);

  if (phase === "scanning") {
    return (
      <div className="flex flex-col gap-3" data-testid="qr-scanner">
        <div className="relative aspect-square w-full overflow-hidden rounded-2xl bg-muted">
          <video ref={video} muted playsInline className="size-full object-cover" aria-label={t("videoLabel")} />
          <div
            aria-hidden
            className={cn(
              "pointer-events-none absolute inset-[14%] rounded-2xl border-4",
              seen === "found" ? "border-success" : seen === "notACode" ? "border-destructive" : "border-white",
            )}
          />
        </div>
        <p role="status" aria-live="polite" className={cn("text-center text-sm font-medium", seen === "notACode" && "text-destructive")} data-testid="qr-status">
          {seen === "found" ? t("found") : seen === "notACode" ? t("notACode") : t("hint")}
        </p>
        <Button
          type="button"
          variant="outline"
          onClick={() => {
            stop();
            setPhase("idle");
            setSeen("looking");
          }}
        >
          {t("stop")}
        </Button>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3" data-testid="qr-idle">
      <p className="text-sm text-muted-foreground">{phase === "denied" ? t("denied") : phase === "unavailable" ? t("unavailable") : t("permission")}</p>
      <Button type="button" size="lg" variant="outline" onClick={() => void start()} disabled={phase === "starting"} data-testid="qr-start">
        <CameraIcon aria-hidden />
        {phase === "starting" ? t("starting") : t("start")}
      </Button>
    </div>
  );
}
