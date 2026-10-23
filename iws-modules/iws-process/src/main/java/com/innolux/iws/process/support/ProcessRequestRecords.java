package com.innolux.iws.process.support;

import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessRequestDetail;
import com.innolux.iws.process.domain.ProcessTriggerReceipt;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.runtime.ActionResult;

import java.time.LocalDateTime;
import java.util.UUID;

/** 方向观测只记录真实交互的安全关联，不持久化原始报文或凭据。 */
public final class ProcessRequestRecords {
    private ProcessRequestRecords() {
    }

    public static ProcessRequestDetail inbound(ProcessTriggerReceipt receipt, ProcessEvent event) {
        ProcessRequestDetail row = common(event);
        row.setRequestId(UUID.randomUUID().toString());
        row.setDirection(EventDirection.INBOUND);
        row.setPurpose(receipt.getPurpose());
        row.setSourceRequestId(receipt.getSourceRequestId());
        row.setCallerSystemCode(receipt.getCallerSystemCode());
        row.setModuleCode(receipt.getModuleCode());
        row.setReceiptId(receipt.getId());
        row.setSent(false);
        row.setResultCode("RECEIVED");
        row.setRequestSummary("入站已持久受理；载荷摘要=" + receipt.getPayloadHash());
        return row;
    }

    public static ProcessRequestDetail outbound(ProcessEvent event, Long attemptId, ActionResult result,
                                                 String requestId, LocalDateTime startedAt,
                                                 com.innolux.iws.process.enums.CallPurpose purpose) {
        ProcessRequestDetail row = common(event);
        row.setRequestId(requestId);
        row.setDirection(EventDirection.OUTBOUND);
        row.setPurpose(purpose);
        row.setAttemptId(attemptId);
        row.setSent(true);
        row.setHttpStatus(result.httpStatus());
        row.setEffectResult(result.effectResult());
        row.setResultCode(result.effectResult().name());
        row.setRequestSummary("单动作出站；输入摘要=" + event.getInputHash());
        row.setResponseSummary("结果=" + result.effectResult().name() + "；证据="
            + (result.evidenceRef() == null ? "未确认" : result.evidenceRef()));
        row.setStartedAt(startedAt);
        return row;
    }

    private static ProcessRequestDetail common(ProcessEvent event) {
        ProcessRequestDetail row = new ProcessRequestDetail();
        row.setInstanceId(event.getInstanceId());
        row.setEventId(event.getId());
        row.setContractKey(event.getCallContractKey());
        row.setContractVersion(event.getCallContractVersion());
        row.setEffectKey(event.getEffectKey());
        LocalDateTime now = LocalDateTime.now();
        row.setStartedAt(now);
        row.setEndedAt(now);
        row.setEvidenceAt(now);
        return row;
    }
}
