import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { PlanRetry } from "../PlanRetry";

const get = jest.fn();
const post = jest.fn();
jest.mock("../../../config/axiosConfig", () => ({
  axiosAuxiliary: {
    get: (...args: unknown[]) => get(...args),
    post: (...args: unknown[]) => post(...args),
  },
}));
jest.mock("../outputUrl", () => ({ getPublicApiOrigin: () => "https://portal.example.test" }));

beforeEach(() => {
  get.mockReset();
  post.mockReset();
});

it("requests one retry of the same run and waits for the controller", async () => {
  const info = { attempt: 1, available: true, requested: false, history: [] };
  get.mockResolvedValue({ data: info });
  let resolve!: () => void;
  post.mockReturnValue(
    new Promise<void>((done) => {
      resolve = done;
    })
  );
  render(<PlanRetry jobId="7087" organizationId="org" status="failed" onUpdate={jest.fn()} />);
  const button = await screen.findByRole("button", { name: "Retry plan" });
  fireEvent.click(button);
  fireEvent.click(button);
  expect(post).toHaveBeenCalledTimes(1);
  expect(post).toHaveBeenCalledWith("https://portal.example.test/api/retry/v1/jobs/7087/plan", { expectedAttempt: 1 });
  get.mockResolvedValue({ data: { ...info, requested: true, message: "Resuming GitHub Actions" } });
  resolve();
  expect(await screen.findByText("Resuming GitHub Actions")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /Retry requested/ })).toBeDisabled();
});

it("disables retry after an apply has started", async () => {
  get.mockResolvedValue({
    data: { attempt: 1, available: false, reason: "A downstream step already executed", requested: false, history: [] },
  });
  render(<PlanRetry jobId="7087" organizationId="org" status="failed" onUpdate={jest.fn()} />);
  expect(await screen.findByText("A downstream step already executed")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Retry plan" })).toBeDisabled();
});

it("shows previous attempts after a successful plan", async () => {
  get.mockResolvedValue({
    data: {
      attempt: 2,
      available: false,
      requested: false,
      history: [
        { attempt: 1, status: "failed", steps: [{ id: "old-step", name: "Pull request plan", status: "failed" }] },
      ],
    },
  });
  render(<PlanRetry jobId="7087" organizationId="org" status="waitingApproval" onUpdate={jest.fn()} />);
  expect(await screen.findByText("Plan attempt 2")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Retry plan" })).not.toBeInTheDocument();
  expect(screen.getByText("Attempt 1")).toBeInTheDocument();
});
