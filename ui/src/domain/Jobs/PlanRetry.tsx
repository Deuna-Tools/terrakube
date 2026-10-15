import { HistoryOutlined, ReloadOutlined } from "@ant-design/icons";
import { Alert, Button, Collapse, Space, Tag, Typography, message } from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { axiosAuxiliary } from "../../config/axiosConfig";
import { getPublicApiOrigin } from "./outputUrl";

type PreviousStep = { id: string; name: string; status: string; output: string };
type RetryInfo = {
  attempt: number;
  available: boolean;
  reason?: string;
  requested: boolean;
  message?: string;
  history: { attempt: number; status: string; retriedBy: string; steps: PreviousStep[] }[];
};

export function PlanRetry({
  jobId,
  organizationId,
  status,
  onUpdate,
}: {
  jobId: string;
  organizationId: string;
  status: string;
  onUpdate: () => void;
}) {
  const [info, setInfo] = useState<RetryInfo>();
  const [submitting, setSubmitting] = useState(false);
  const [logs, setLogs] = useState<Record<string, string>>({});
  const observedAttempt = useRef<number | undefined>(undefined);
  const endpoint = `${getPublicApiOrigin()}/api/retry/v1/jobs/${encodeURIComponent(jobId)}`;
  const refresh = useCallback(
    async (signal?: AbortSignal) => {
      try {
        const response = await axiosAuxiliary.get(endpoint, { signal });
        const next = response.data as RetryInfo;
        if (signal?.aborted || !Number.isInteger(next.attempt) || !Array.isArray(next.history)) return;
        setInfo(next);
        if (observedAttempt.current != null && observedAttempt.current !== next.attempt) onUpdate();
        observedAttempt.current = next.attempt;
      } catch {
        // Retry is an optional API capability during rolling upgrades.
      }
    },
    [endpoint, onUpdate]
  );

  useEffect(() => {
    const controller = new AbortController();
    setInfo(undefined);
    setLogs({});
    observedAttempt.current = undefined;
    void refresh(controller.signal);
    const timer = setInterval(() => void refresh(controller.signal), 5000);
    return () => {
      controller.abort();
      clearInterval(timer);
    };
  }, [refresh, status]);

  const retry = async () => {
    if (!info || submitting || info.requested) return;
    setSubmitting(true);
    try {
      await axiosAuxiliary.post(`${endpoint}/plan`, { expectedAttempt: info.attempt });
      message.success("Plan retry requested. This run and its previous logs are preserved.");
      await refresh();
      onUpdate();
    } catch (error: any) {
      message.error(error?.response?.data?.message ?? "Could not retry the plan. Refresh the run before trying again.");
    } finally {
      setSubmitting(false);
    }
  };

  const loadLogs = async (step: PreviousStep) => {
    const key = step.id;
    setLogs((previous) => ({ ...previous, [key]: "Loading logs…" }));
    try {
      const response = await axiosAuxiliary.get(
        `${getPublicApiOrigin()}/tfoutput/v1/organization/${encodeURIComponent(organizationId)}/job/${encodeURIComponent(jobId)}/step/${encodeURIComponent(key)}`
      );
      setLogs((previous) => ({
        ...previous,
        [key]: typeof response.data === "string" ? response.data : JSON.stringify(response.data),
      }));
    } catch {
      setLogs((previous) => ({ ...previous, [key]: "Logs are unavailable." }));
    }
  };

  if (!info || (!["failed", "cancelled"].includes(status) && info.attempt === 1)) return null;
  return (
    <Space direction="vertical" style={{ width: "100%" }}>
      <Space wrap>
        <Tag>Plan attempt {info.attempt}</Tag>
        {["failed", "cancelled"].includes(status) && (
          <Button
            type="primary"
            icon={<ReloadOutlined aria-hidden="true" />}
            loading={submitting || info.requested}
            disabled={!info.available || info.requested}
            onClick={retry}
          >
            {info.requested ? "Retry requested" : "Retry plan"}
          </Button>
        )}
        <Typography.Text type="secondary">Same run · same commit</Typography.Text>
      </Space>
      {info.requested && (
        <Alert type="info" showIcon message={info.message ?? "Waiting for the GitHub controller to resume"} />
      )}
      {!info.requested && !info.available && ["failed", "cancelled"].includes(status) && (
        <Typography.Text type="secondary">{info.reason}</Typography.Text>
      )}
      {info.history.length > 0 && (
        <Collapse
          items={info.history.map((attempt) => ({
            key: String(attempt.attempt),
            label: (
              <Space>
                <HistoryOutlined />
                <span>Attempt {attempt.attempt}</span>
                <Tag color="error">{attempt.status}</Tag>
              </Space>
            ),
            children: (
              <Space direction="vertical" style={{ width: "100%" }}>
                {attempt.steps.map((step) => (
                  <div key={step.id} style={{ width: "100%" }}>
                    <Space wrap>
                      <Typography.Text>{step.name}</Typography.Text>
                      <Tag>{step.status}</Tag>
                      <Button size="small" onClick={() => void loadLogs(step)}>
                        View logs
                      </Button>
                    </Space>
                    {logs[step.id] && (
                      <pre style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{logs[step.id]}</pre>
                    )}
                  </div>
                ))}
              </Space>
            ),
          }))}
        />
      )}
    </Space>
  );
}
