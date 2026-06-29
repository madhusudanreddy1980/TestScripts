# Convenience targets. Override env on the fly, e.g.:
#   make smoke TEST_ENV=uat REPORT_TYPE=html
TEST_ENV    ?= qa
REPORT_TYPE ?= allure
export TEST_ENV REPORT_TYPE

.PHONY: install browsers offline smoke regression e2e api db gui integration all report clean

install:
	pip install -r requirements.txt

browsers:
	python -m playwright install chromium

# Runs without browser/DB/network — good for CI gating and local sanity.
offline:
	pytest tests/regression/test_framework_core.py tests/regression/test_prompt_layer.py -v

smoke:
	pytest -m smoke -v

regression:
	pytest -m regression -n auto -v

e2e:
	pytest -m e2e -v

api:
	pytest -m api -v

db:
	pytest -m db -v

gui:
	pytest -m gui -v

integration:
	pytest -m integration -v

all:
	pytest -n auto -v

report:
	python -m framework.reporting.report_manager

clean:
	rm -rf reports/allure-results reports/allure-report reports/html reports/screenshots .pytest_cache
