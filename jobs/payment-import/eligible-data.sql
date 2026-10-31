-- Payments waiting for export that have not already been posted to the ledger.
SELECT p.payment_ref,
       p.debit_account,
       p.amount,
       p.currency,
       p.value_date,
       p.narrative,
       p.status
FROM payments_src p
WHERE p.status = :status
  AND p.amount > 0
  AND NOT EXISTS (SELECT 1 FROM payment_ledger l WHERE l.payment_ref = p.payment_ref)
ORDER BY p.payment_ref
