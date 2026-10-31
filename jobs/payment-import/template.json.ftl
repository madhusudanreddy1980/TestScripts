<#-- FreeMarker, plain output. Every value (and every context variable) arrives
     pre-encoded as a JSON literal: strings quoted and escaped, numbers exact, null as null. -->
{
  "batchHeader": {
    "jobId": ${job_id},
    "runId": ${run_id},
    "schemaVersion": ${schema_version},
    "recordCount": ${records?size?c}
  },
  "payments": [
<#list records as r>
    {
      "paymentRef": ${r.payment_ref},
      "correlationId": ${r.correlation_id},
      "debitAccount": ${r.account_no},
      "amount": ${r.amount},
      "currency": ${r.currency},
      "valueDate": ${r.value_date},
      "narrative": ${r.narrative}
    }<#sep>,</#sep>
</#list>
  ]
}
