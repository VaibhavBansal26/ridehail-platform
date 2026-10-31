import { type AuthResult, saveSession } from "@ridehail/api";
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { api, onSignedOut } from "@/lib/api";
import { tokenStore } from "@/lib/tokens";

export type SessionState = "loading" | "signedOut" | "signedIn";
type Ctx = {
  state: SessionState;
  signIn: (phone: string, password: string) => Promise<void>;
  register: (b: { name: string; phone: string; password: string; vehicleType: "BIKE" | "AUTO" | "SEDAN" | "SUV" }) => Promise<void>;
  signOut: () => Promise<void>;
};

const SessionContext = createContext<Ctx | null>(null);
export function useSession(): Ctx {
  const ctx = useContext(SessionContext);
  if (!ctx) throw new Error("useSession outside SessionProvider");
  return ctx;
}

/** A rider app only ever holds a rider's session: any other account is turned away like a wrong password. */
export class WrongAccountError extends Error {
  constructor() {
    super("Wrong phone or password");
  }
}

async function accept(result: AuthResult): Promise<void> {
  if (result.role !== "RIDER") throw new WrongAccountError();
  await saveSession(tokenStore, result);
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SessionState>("loading");

  useEffect(() => {
    onSignedOut(() => setState("signedOut"));
    let live = true;
    void Promise.resolve(tokenStore.load()).then((t) => live && setState(t ? "signedIn" : "signedOut"));
    return () => {
      live = false;
    };
  }, []);

  const signIn = useCallback(async (phone: string, password: string) => {
    await accept(await api.auth.login({ phone: phone.trim(), password }));
    setState("signedIn");
  }, []);

  const register: Ctx["register"] = useCallback(async (b) => {
    await accept(await api.auth.registerRider({ ...b, name: b.name.trim(), phone: b.phone.trim() }));
    setState("signedIn");
  }, []);

  const signOut = useCallback(async () => {
    await tokenStore.save(null);
    setState("signedOut");
  }, []);

  const value = useMemo(() => ({ state, signIn, register, signOut }), [state, signIn, register, signOut]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}
