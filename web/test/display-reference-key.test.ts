import { describe, expect, it } from "vitest";
import { displayReferenceKey } from "@/lib/display-reference-key";

describe("bibliography display keys", () => {
  it.each([
    {
      references: [
        { localReferenceKey: "b0", entryOrder: 0 },
        { localReferenceKey: "b7", entryOrder: 1 },
      ],
    },
    {
      references: [
        { localReferenceKey: "b1", entryOrder: 1 },
        { localReferenceKey: "b7", entryOrder: 2 },
      ],
    },
  ])("resolves a projected reference order from the complete collection", ({ references }) => {
    expect(displayReferenceKey({ localReferenceKey: "b7" }, references)).toBe("b2");
  });

  it("keeps the original key when entry order cannot establish a complete collection", () => {
    const incompleteReferences = [
      { localReferenceKey: "b3", entryOrder: 0 },
      { localReferenceKey: "b7", entryOrder: 2 },
    ];
    const completeReferences = [
      { localReferenceKey: "b0", entryOrder: 0 },
      { localReferenceKey: "b1", entryOrder: 1 },
    ];

    expect(displayReferenceKey({ localReferenceKey: "b7" }, incompleteReferences)).toBe("b7");
    expect(displayReferenceKey({ localReferenceKey: "b9", entryOrder: 9 }, completeReferences)).toBe("b9");
    expect(displayReferenceKey({ localReferenceKey: "ref1", entryOrder: 1 }, incompleteReferences)).toBe("ref1");
  });
});
