export type ReferenceLabelSource = {
  localReferenceKey: string;
  entryOrder?: number;
};

function referenceOrderBase(references: readonly ReferenceLabelSource[]): number | null {
  if (references.length === 0) return null;

  const keys = new Set<string>();
  const orders: number[] = [];
  for (const reference of references) {
    const order = reference.entryOrder;
    if (keys.has(reference.localReferenceKey) || typeof order !== "number" || !Number.isSafeInteger(order)) return null;
    keys.add(reference.localReferenceKey);
    orders.push(order);
  }

  orders.sort((first, second) => first - second);
  const base = orders[0];
  if (base !== 0 && base !== 1) return null;
  if (!orders.every((order, index) => order === base + index)) return null;
  return base;
}

export function displayReferenceKey(
  reference: ReferenceLabelSource,
  references: readonly ReferenceLabelSource[],
): string {
  const generatedKey = /^([bB])\d+$/.exec(reference.localReferenceKey);
  if (!generatedKey) return reference.localReferenceKey;

  const matchingReference = references.find(({ localReferenceKey }) => localReferenceKey === reference.localReferenceKey);
  const referenceEntryOrder = reference.entryOrder;
  const order = typeof referenceEntryOrder === "number" && Number.isSafeInteger(referenceEntryOrder)
    ? referenceEntryOrder
    : matchingReference?.entryOrder;
  const orderBase = referenceOrderBase(references);
  if (typeof order !== "number" || !Number.isSafeInteger(order) || orderBase === null) return reference.localReferenceKey;
  if (matchingReference && matchingReference.entryOrder !== order) return reference.localReferenceKey;

  const displayOrder = order - orderBase + 1;
  if (displayOrder < 1 || displayOrder > references.length) return reference.localReferenceKey;
  return `${generatedKey[1]}${displayOrder}`;
}
