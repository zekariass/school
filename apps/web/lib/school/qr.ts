import { create } from "qrcode";

export interface QrMatrix {
  size: number;
  /** Row-major: `true` for dark modules. */
  dark: boolean[];
}

/** The QR modules of `text`, computed locally (nothing is sent anywhere). Medium error correction. */
export function qrMatrix(text: string): QrMatrix {
  const code = create(text, { errorCorrectionLevel: "M" });
  const { size, data } = code.modules;
  return { size, dark: Array.from(data, (v) => v === 1) };
}

/** One SVG path (1 unit per module) drawing every dark module. */
export function qrPath(matrix: QrMatrix): string {
  const parts: string[] = [];
  for (let y = 0; y < matrix.size; y++) {
    let x = 0;
    while (x < matrix.size) {
      if (!matrix.dark[y * matrix.size + x]) {
        x++;
        continue;
      }
      const start = x;
      while (x < matrix.size && matrix.dark[y * matrix.size + x]) x++;
      parts.push(`M${start} ${y}h${x - start}v1h-${x - start}z`);
    }
  }
  return parts.join("");
}
