import * as SecureStore from "expo-secure-store";
import { tokenStore } from "@/lib/tokens";

const store = SecureStore as jest.Mocked<typeof SecureStore>;
beforeEach(() => jest.clearAllMocks());

describe("token storage (iOS keychain / Android keystore)", () => {
  it("keeps both tokens together in secure storage", async () => {
    await tokenStore.save({ accessToken: "A", refreshToken: "R" });
    expect(store.setItemAsync).toHaveBeenCalledWith(expect.any(String), JSON.stringify({ accessToken: "A", refreshToken: "R" }));
  });

  it("reads them back, and treats anything unusable as signed out", async () => {
    store.getItemAsync.mockResolvedValueOnce(JSON.stringify({ accessToken: "A", refreshToken: "R" }));
    expect(await tokenStore.load()).toEqual({ accessToken: "A", refreshToken: "R" });
    for (const junk of [null, "", "{", "[]", JSON.stringify({ accessToken: "A" }), JSON.stringify({ accessToken: 1, refreshToken: 2 })]) {
      store.getItemAsync.mockResolvedValueOnce(junk);
      expect(await tokenStore.load()).toBeNull();
    }
  });

  it("is signed out, not crashed, when the keychain cannot be read", async () => {
    store.getItemAsync.mockRejectedValueOnce(new Error("keychain locked"));
    expect(await tokenStore.load()).toBeNull();
  });

  it("deletes them on sign-out", async () => {
    await tokenStore.save(null);
    expect(store.deleteItemAsync).toHaveBeenCalled();
    expect(store.setItemAsync).not.toHaveBeenCalled();
  });
});
