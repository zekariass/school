import { describe, expect, it } from "vitest";
import { importDateSystems, initialDateSystem } from "./date-system";

describe("importDateSystems", () => {
  it("offers Ethiopian only where the country has that calendar", () => {
    expect(importDateSystems(["ETHIOPIAN", "GREGORIAN"])).toEqual(["GREGORIAN", "ETHIOPIAN"]);
    expect(importDateSystems(["GREGORIAN"])).toEqual(["GREGORIAN"]);
    expect(importDateSystems([])).toEqual(["GREGORIAN"]);
  });
});

describe("initialDateSystem", () => {
  it("keeps a saved choice the country still offers and otherwise starts with Gregorian", () => {
    expect(initialDateSystem("ETHIOPIAN", ["GREGORIAN", "ETHIOPIAN"])).toBe("ETHIOPIAN");
    expect(initialDateSystem("ETHIOPIAN", ["GREGORIAN"])).toBe("GREGORIAN");
    expect(initialDateSystem(undefined, ["GREGORIAN", "ETHIOPIAN"])).toBe("GREGORIAN");
  });
});
