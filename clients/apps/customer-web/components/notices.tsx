"use client";

import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useState } from "react";

export type Notice = { text: string; tone: "info" | "ok" | "bad" };
type Ctx = { notice: Notice | null; show: (text: string, tone?: Notice["tone"]) => void; clear: () => void };

const NoticeContext = createContext<Ctx>({ notice: null, show: () => undefined, clear: () => undefined });
export const useNotices = () => useContext(NoticeContext);

/** One short message at a time ("The customer cancelled this trip", "You earned $7.60"), gone after a few seconds. */
export function NoticeProvider({ children }: { children: ReactNode }) {
  const [notice, setNotice] = useState<Notice | null>(null);
  const show = useCallback((text: string, tone: Notice["tone"] = "info") => setNotice({ text, tone }), []);
  const clear = useCallback(() => setNotice(null), []);
  useEffect(() => {
    if (!notice) return;
    const t = setTimeout(() => setNotice(null), 8000);
    return () => clearTimeout(t);
  }, [notice]);
  const value = useMemo(() => ({ notice, show, clear }), [notice, show, clear]);
  return <NoticeContext.Provider value={value}>{children}</NoticeContext.Provider>;
}
