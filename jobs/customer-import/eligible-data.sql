-- Active customers with an email in a supported country.
-- Parameters are bound by the framework; it wraps this query for paging.
SELECT customer_id,
       first_name,
       last_name,
       email,
       date_of_birth,
       country_code,
       credit_limit,
       status
FROM customers_src
WHERE status = :status
  AND email IS NOT NULL
  AND UPPER(country_code) IN ('IN', 'US', 'GB')
ORDER BY customer_id
