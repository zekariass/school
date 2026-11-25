"use client";

import { useMemo } from "react";
import { qrMatrix, qrPath } from "@/lib/school/qr";

/** A QR code drawn locally as SVG (black on white, with the quiet zone). Nothing is sent anywhere. */
export function QrCode({ value, size = 128, label }: { value: string; size?: number; label: string }) {
  const { path, modules } = useMemo(() => {
    const matrix = qrMatrix(value);
    return { path: qrPath(matrix), modules: matrix.size };
  }, [value]);
  const quiet = 3;
  const total = modules + quiet * 2;
  return (
    <svg
      role="img"
      aria-label={label}
      width={size}
      height={size}
      viewBox={`0 0 ${total} ${total}`}
      shapeRendering="crispEdges"
      className="bg-white"
    >
      <path d={path} transform={`translate(${quiet} ${quiet})`} fill="#000" />
    </svg>
  );
}
