export type Capability = {
  descriptor: {id: string; version: number; title: string; description: string; inputSchema: Record<string, unknown>; risk: string};
  availability: string;
  detail?: string;
};

type FullCatalog = {revision: string | null; scopedDiscovery: boolean; capabilities: Capability[]};
type MatchingCatalog = {revision: string; scopedDiscovery: boolean; unchanged: true};
export type CatalogReply = FullCatalog | MatchingCatalog;

export async function readCatalog(storage: DurableObjectStorage, knownRevision?: string | null): Promise<CatalogReply> {
  if (knownRevision) {
    const metadata = await storage.get(['catalogRevision', 'scopedDiscovery']);
    if (metadata.get('catalogRevision') === knownRevision) {
      return {revision: knownRevision, scopedDiscovery: metadata.get('scopedDiscovery') === true, unchanged: true};
    }
  }
  // Read all three keys together. A newer hello can commit between the metadata check and this read.
  const stored = await storage.get(['catalogRevision', 'capabilities', 'scopedDiscovery']);
  return {
    revision: (stored.get('catalogRevision') as string | undefined) ?? null,
    scopedDiscovery: stored.get('scopedDiscovery') === true,
    capabilities: (stored.get('capabilities') as Capability[] | undefined) ?? []
  };
}
