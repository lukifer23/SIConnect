export type DiscoveryScope = {allowed: string[]; status: 'OBSERVED' | 'NO_SESSION' | 'UNCONFIRMED'};

type PendingScope = {
  epoch: string;
  agent: string;
  credentialHash: string;
  result: Promise<DiscoveryScope>;
  finish: (scope: DiscoveryScope) => void;
};

/** Shares only pending reads. A completed reply is never an authorization cache. */
export class DiscoveryReads {
  private readonly pending = new Map<string, PendingScope>();

  read(epoch: string, agent: string, credentialHash: string, send: (id: string) => void): Promise<DiscoveryScope> {
    for (const read of this.pending.values()) {
      if (read.epoch === epoch && read.agent === agent && read.credentialHash === credentialHash) return read.result;
    }
    if (this.pending.size >= 32) return Promise.resolve({allowed: [], status: 'UNCONFIRMED'});

    const id = crypto.randomUUID();
    let resolve!: (scope: DiscoveryScope) => void;
    const result = new Promise<DiscoveryScope>(done => { resolve = done; });
    const finish = (scope: DiscoveryScope) => {
      clearTimeout(timer);
      this.pending.delete(id);
      resolve(scope);
    };
    const timer = setTimeout(() => finish({allowed: [], status: 'UNCONFIRMED'}), 4000);
    this.pending.set(id, {epoch, agent, credentialHash, result, finish});
    try { send(id); } catch { finish({allowed: [], status: 'UNCONFIRMED'}); }
    return result;
  }

  answer(epoch: string, id: string, allowed: string[]): void {
    const read = this.pending.get(id);
    if (read?.epoch === epoch) read.finish({allowed, status: 'OBSERVED'});
  }

  disconnect(epoch?: string): void {
    for (const read of this.pending.values()) {
      if (epoch === undefined || read.epoch === epoch) read.finish({allowed: [], status: 'UNCONFIRMED'});
    }
  }
}
