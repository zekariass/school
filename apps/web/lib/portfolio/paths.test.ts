import { describe, expect, it } from "vitest";
import { familyPortfolioPath, itemPath, workspaceItemPath, workspacePortfolioPath } from "./paths";

describe("portfolio paths", () => {
  it("put ids only into a path", () => {
    expect(itemPath("sch", "item")).toBe("/portfolio/items/sch/item");
    expect(workspaceItemPath("sunrise", "item")).toBe("/s/sunrise/portfolio/items/item");
    expect(workspacePortfolioPath("sunrise")).toBe("/s/sunrise/portfolio");
    expect(familyPortfolioPath()).toBe("/family/portfolio");
    expect(familyPortfolioPath("kid")).toBe("/family/portfolio/kid");
  });

  it("encodes a slug", () => {
    expect(workspaceItemPath("a b", "i")).toBe("/s/a%20b/portfolio/items/i");
  });
});
