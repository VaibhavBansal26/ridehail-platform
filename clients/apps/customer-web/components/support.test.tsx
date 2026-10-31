import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SupportView } from "@/components/support-view";
import { api } from "@/lib/api";
import { renderApp } from "@/test/render";

vi.mock("@/lib/api", () => ({ api: { support: { conversation: vi.fn(), send: vi.fn(), markRead: vi.fn() } } }));
const support = api.support as unknown as Record<string, ReturnType<typeof vi.fn>>;

const msg = (id: string, from: "USER" | "STAFF", body: string) => ({ id, from, body, tripId: null, createdAt: "2026-10-31T10:00:00Z" });
const conversation = (over: Record<string, unknown> = {}) => ({ id: "c1", status: "OPEN", unread: 0, messages: [] as unknown[], ...over });

beforeEach(() => {
  vi.clearAllMocks();
  support.conversation.mockResolvedValue(conversation());
  support.markRead.mockResolvedValue(undefined);
});

describe("support chat", () => {
  it("says so when nothing has been written yet", async () => {
    renderApp(<SupportView />);
    expect(await screen.findByText("No messages yet.")).toBeInTheDocument();
  });

  it("shows both sides of the conversation as plain text", async () => {
    support.conversation.mockResolvedValue(conversation({ messages: [msg("1", "USER", "Where is my refund?"), msg("2", "STAFF", "<b>Done</b> today.")] }));
    renderApp(<SupportView />);
    const list = await screen.findByRole("list", { name: "Messages" });
    expect(list).toHaveTextContent("Where is my refund?");
    expect(list).toHaveTextContent("<b>Done</b> today.");
    expect(list.querySelector("b")).toBeNull();
  });

  it("sends a trimmed message and shows the updated conversation", async () => {
    const user = userEvent.setup();
    support.send.mockResolvedValue(conversation({ messages: [msg("1", "USER", "Hello")] }));
    renderApp(<SupportView />);
    const box = await screen.findByRole("textbox", { name: /Your message/ });
    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
    await user.type(box, "  Hello  ");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(support.send).toHaveBeenCalledWith("Hello"));
    expect(await screen.findByRole("list", { name: "Messages" })).toHaveTextContent("Hello");
    expect(box).toHaveValue("");
  });

  it("keeps what was typed when sending fails, and says why", async () => {
    const user = userEvent.setup();
    support.send.mockRejectedValue(new Error("You have sent a lot of messages."));
    renderApp(<SupportView />);
    const box = await screen.findByRole("textbox", { name: /Your message/ });
    await user.type(box, "Please help");
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByText(/sent a lot of messages/)).toBeInTheDocument();
    expect(box).toHaveValue("Please help");
  });

  it("marks replies read once they are on screen", async () => {
    support.conversation.mockResolvedValue(conversation({ unread: 2, messages: [msg("1", "STAFF", "Hi")] }));
    renderApp(<SupportView />);
    await waitFor(() => expect(support.markRead).toHaveBeenCalledTimes(1));
  });

  it("does not ask to mark anything read when there is nothing new", async () => {
    support.conversation.mockResolvedValue(conversation({ messages: [msg("1", "USER", "Hi")] }));
    renderApp(<SupportView />);
    await screen.findByText("Hi", { exact: false });
    expect(support.markRead).not.toHaveBeenCalled();
  });

  it("invites a new message after a resolution", async () => {
    support.conversation.mockResolvedValue(conversation({ status: "RESOLVED", messages: [msg("1", "STAFF", "Fixed")] }));
    renderApp(<SupportView />);
    expect(await screen.findByText(/Marked resolved/)).toBeInTheDocument();
  });
});
