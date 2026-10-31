import { fireEvent, screen, waitFor } from "@testing-library/react-native";
import { SupportView } from "@/components/support-view";
import { api } from "@/lib/api";
import { renderApp } from "@/test-utils";

jest.mock("@/lib/api", () => ({ api: { support: { conversation: jest.fn(), send: jest.fn(), markRead: jest.fn() } } }));
const support = api.support as unknown as Record<string, jest.Mock>;

const msg = (id: string, from: "USER" | "STAFF", body: string) => ({ id, from, body, tripId: null, createdAt: "2026-10-31T10:00:00Z" });
const conversation = (over: Record<string, unknown> = {}) => ({ id: "c1", status: "OPEN", unread: 0, messages: [] as unknown[], ...over });

beforeEach(() => {
  jest.clearAllMocks();
  support.conversation.mockResolvedValue(conversation());
  support.markRead.mockResolvedValue(undefined);
});

describe("support chat", () => {
  it("says so when nothing has been written yet", async () => {
    await renderApp(<SupportView />);
    expect(await screen.findByText("No messages yet.")).toBeTruthy();
  });

  it("shows both sides of the conversation", async () => {
    support.conversation.mockResolvedValue(conversation({ messages: [msg("1", "USER", "Where is my refund?"), msg("2", "STAFF", "<b>Done</b> today.")] }));
    await renderApp(<SupportView />);
    expect(await screen.findByText("Where is my refund?")).toBeTruthy();
    expect(screen.getByText("<b>Done</b> today.")).toBeTruthy();
  });

  it("sends a trimmed message and clears the box", async () => {
    support.send.mockResolvedValue(conversation({ messages: [msg("1", "USER", "Hello")] }));
    await renderApp(<SupportView />);
    const box = await screen.findByLabelText("Your message");
    await fireEvent.changeText(box, "  Hello  ");
    await fireEvent.press(screen.getByText("Send"));
    await waitFor(() => expect(support.send).toHaveBeenCalledWith("Hello"));
    expect(await screen.findByText("Hello")).toBeTruthy();
    expect(screen.getByLabelText("Your message").props.value).toBe("");
  });

  it("keeps what was typed when sending fails, and says why", async () => {
    support.send.mockRejectedValue(new Error("You have sent a lot of messages."));
    await renderApp(<SupportView />);
    await fireEvent.changeText(await screen.findByLabelText("Your message"), "Please help");
    await fireEvent.press(screen.getByText("Send"));
    expect(await screen.findByText(/sent a lot of messages/)).toBeTruthy();
    expect(screen.getByLabelText("Your message").props.value).toBe("Please help");
  });

  it("marks replies read once they are on screen, and only then", async () => {
    support.conversation.mockResolvedValue(conversation({ unread: 2, messages: [msg("1", "STAFF", "Hi")] }));
    await renderApp(<SupportView />);
    await waitFor(() => expect(support.markRead).toHaveBeenCalledTimes(1));
  });

  it("does not mark anything read when there is nothing new", async () => {
    support.conversation.mockResolvedValue(conversation({ messages: [msg("1", "USER", "Hi")] }));
    await renderApp(<SupportView />);
    await screen.findByText("Hi");
    expect(support.markRead).not.toHaveBeenCalled();
  });
});
