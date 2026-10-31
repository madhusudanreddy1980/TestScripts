<?xml version="1.0" encoding="UTF-8"?>
<CustomerBatch xmlns="urn:example:batch:customer:v1" schemaVersion="${schema_version}" runId="${run_id}" recordCount="${records?size?c}">
<#list records as r>
  <Customer customerId="${r.customer_id!}">
    <BatchRef>${r.batch_ref!}</BatchRef>
<#if r.first_name??>
    <FirstName>${r.first_name}</FirstName>
</#if>
<#if r.last_name??>
    <LastName>${r.last_name}</LastName>
</#if>
<#if r.email??>
    <Email>${r.email}</Email>
</#if>
<#if r.date_of_birth??>
    <DateOfBirth>${r.date_of_birth}</DateOfBirth>
</#if>
<#if r.country??>
    <Country>${r.country}</Country>
</#if>
<#if r.credit_limit??>
    <CreditLimit>${r.credit_limit}</CreditLimit>
</#if>
  </Customer>
</#list>
</CustomerBatch>
