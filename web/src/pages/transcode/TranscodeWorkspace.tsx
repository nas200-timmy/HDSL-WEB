import {
  createContext,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import type { TranscodeMediaEntry } from "../../api";

export type StagedItem = {
  entry: TranscodeMediaEntry;
  output?: string;
  outputAbsolute?: string;
  error?: string;
};

interface TranscodeWorkspaceValue {
  staged: StagedItem[];
  presetId: string;
  outputDir: string;
  addEntries: (entries: TranscodeMediaEntry[]) => void;
  setStaged: (items: StagedItem[]) => void;
  removeStaged: (paths: string[]) => void;
  clearStaged: () => void;
  setPresetId: (id: string) => void;
  setOutputDir: (path: string) => void;
}

const TranscodeWorkspace = createContext<TranscodeWorkspaceValue | null>(null);
const STAGED_KEY = "transcode.staged";
const PRESET_KEY = "transcode.presetId";
const OUTPUT_KEY = "transcode.outputDir";

function readStaged(): StagedItem[] {
  try {
    const value = JSON.parse(sessionStorage.getItem(STAGED_KEY) ?? "[]") as unknown;
    if (!Array.isArray(value)) return [];
    return value.filter((item): item is StagedItem => {
      if (!item || typeof item !== "object") return false;
      const candidate = item as Partial<StagedItem>;
      return Boolean(candidate.entry?.path && candidate.entry.name && !candidate.entry.directory);
    });
  } catch {
    return [];
  }
}

function store(key: string, value: string) {
  try {
    sessionStorage.setItem(key, value);
  } catch {
    // A full/blocked session store must not make the queue controls unusable.
  }
}

/// The file list and output target belong to the whole transcode workspace, not to
/// PreparePage. Switching to the parameter or queue page therefore cannot discard work.
export function TranscodeWorkspaceProvider({ children }: { children: ReactNode }) {
  const [staged, updateStaged] = useState<StagedItem[]>(readStaged);
  const [presetId, updatePresetId] = useState(() => sessionStorage.getItem(PRESET_KEY) ?? "");
  const [outputDir, updateOutputDir] = useState(() => sessionStorage.getItem(OUTPUT_KEY) ?? "");

  const value = useMemo<TranscodeWorkspaceValue>(() => ({
    staged,
    presetId,
    outputDir,
    addEntries(entries) {
      updateStaged((previous) => {
        const seen = new Set(previous.map((item) => item.entry.path));
        const next = [...previous];
        for (const entry of entries) {
          if (entry.directory || seen.has(entry.path)) continue;
          seen.add(entry.path);
          next.push({ entry });
        }
        store(STAGED_KEY, JSON.stringify(next));
        return next;
      });
    },
    setStaged(items) {
      store(STAGED_KEY, JSON.stringify(items));
      updateStaged(items);
    },
    removeStaged(paths) {
      updateStaged((previous) => {
        const removed = new Set(paths);
        const next = previous.filter((item) => !removed.has(item.entry.path));
        store(STAGED_KEY, JSON.stringify(next));
        return next;
      });
    },
    clearStaged() {
      store(STAGED_KEY, "[]");
      updateStaged([]);
    },
    setPresetId(id) {
      if (id) store(PRESET_KEY, id);
      else sessionStorage.removeItem(PRESET_KEY);
      updatePresetId(id);
    },
    setOutputDir(path) {
      store(OUTPUT_KEY, path);
      updateOutputDir(path);
    },
  }), [staged, presetId, outputDir]);

  return <TranscodeWorkspace.Provider value={value}>{children}</TranscodeWorkspace.Provider>;
}

export function useTranscodeWorkspace(): TranscodeWorkspaceValue {
  const value = useContext(TranscodeWorkspace);
  if (!value) throw new Error("transcode workspace is not mounted");
  return value;
}
