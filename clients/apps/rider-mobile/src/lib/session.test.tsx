import { act, renderHook, waitFor } from "@testing-library/react-native";
import type { ReactNode } from "react";
import { api } from "@/lib/api";
import { SessionProvider, useSession, WrongAccountError } from "@/lib/session";
import { tokenStore } from "@/lib/tokens";

jest.mock("@/lib/api", () => ({
  api: { auth: { login: jest.fn(), registerRider: jest.fn() } },
  onSignedOut: jest.fn(),
}));
jest.mock("@/lib/tokens", () => {
  let tokens: unknown = null;
  return { tokenStore: { load: jest.fn(() => tokens), save: jest.fn((t: unknown) => void (tokens = t)) } };
});

const auth = api.auth as jest.Mocked<typeof api.auth>;
const wrapper = ({ children }: { children: ReactNode }) => <SessionProvider>{children}</SessionProvider>;
const result = (role: "RIDER" | "CUSTOMER" | "ADMIN") => ({ accessToken: "A", refreshToken: "R", role, profileId: "p1" });

beforeEach(() => {
  jest.clearAllMocks();
  void tokenStore.save(null);
});

describe("the session", () => {
  it("starts signed out when there are no stored tokens", async () => {
    const { result: s } = await renderHook(() => useSession(), { wrapper });
    await waitFor(() => expect(s.current.state).toBe("signedOut"));
  });

  it("signs a rider in and keeps the tokens in secure storage", async () => {
    auth.login.mockResolvedValue(result("RIDER"));
    const { result: s } = await renderHook(() => useSession(), { wrapper });
    await waitFor(() => expect(s.current.state).toBe("signedOut"));
    await act(async () => s.current.signIn(" +1555 ", "pw"));
    expect(auth.login).toHaveBeenCalledWith({ phone: "+1555", password: "pw" });
    expect(tokenStore.save).toHaveBeenCalledWith({ accessToken: "A", refreshToken: "R" });
    expect(s.current.state).toBe("signedIn");
  });

  it.each(["CUSTOMER", "ADMIN"] as const)("turns a %s account away like a wrong password, and stores nothing", async (role) => {
    auth.login.mockResolvedValue(result(role));
    const { result: s } = await renderHook(() => useSession(), { wrapper });
    await waitFor(() => expect(s.current.state).toBe("signedOut"));
    await act(async () => {
      await expect(s.current.signIn("+1555", "pw")).rejects.toThrow(WrongAccountError);
    });
    expect(tokenStore.save).not.toHaveBeenCalledWith(expect.objectContaining({ accessToken: "A" }));
    expect(s.current.state).toBe("signedOut");
  });

  it("registers a rider and signs in", async () => {
    auth.registerRider.mockResolvedValue(result("RIDER"));
    const { result: s } = await renderHook(() => useSession(), { wrapper });
    await waitFor(() => expect(s.current.state).toBe("signedOut"));
    await act(async () => s.current.register({ name: " Asha ", phone: "+1555", password: "password123", vehicleType: "BIKE" }));
    expect(auth.registerRider).toHaveBeenCalledWith({ name: "Asha", phone: "+1555", password: "password123", vehicleType: "BIKE" });
    expect(s.current.state).toBe("signedIn");
  });

  it("clears the tokens on sign-out", async () => {
    auth.login.mockResolvedValue(result("RIDER"));
    const { result: s } = await renderHook(() => useSession(), { wrapper });
    await waitFor(() => expect(s.current.state).toBe("signedOut"));
    await act(async () => s.current.signIn("+1555", "pw"));
    await act(async () => s.current.signOut());
    expect(tokenStore.save).toHaveBeenLastCalledWith(null);
    expect(s.current.state).toBe("signedOut");
  });
});
