package com.batchauto.data;

import java.util.List;
import java.util.Map;

public record EligibleRow(Object key, Map<String, Object> row, boolean eligible, List<String> reasons) { }
