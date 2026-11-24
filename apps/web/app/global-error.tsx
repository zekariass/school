"use client";

// Rendered only when the root layout itself fails, so i18n context is gone:
// keep it tiny and bilingual.
export default function GlobalError({ retry }: { error: Error; retry: () => void }) {
  return (
    <html lang="en">
      <body
        style={{
          fontFamily: "system-ui, sans-serif",
          display: "grid",
          minHeight: "100dvh",
          placeItems: "center",
          textAlign: "center",
          margin: 0,
        }}
      >
        <main>
          <h1>Something went wrong / የሆነ ችግር ተፈጥሯል</h1>
          <button
            type="button"
            onClick={() => retry()}
            style={{ minHeight: 44, padding: "0 20px", fontSize: 16, borderRadius: 12 }}
          >
            Try again / እንደገና ሞክር
          </button>
        </main>
      </body>
    </html>
  );
}
