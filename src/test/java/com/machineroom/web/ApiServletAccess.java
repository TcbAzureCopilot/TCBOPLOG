package com.machineroom.web;

import com.google.gson.JsonObject;
import com.machineroom.auth.UserPrincipal;
import com.machineroom.model.DailyLog;

/** Test bridge to the package-private JSON helper. */
public final class ApiServletAccess {

    private ApiServletAccess() {
    }

    public static JsonObject logJson(DailyLog log, UserPrincipal user) {
        return ApiServlet.logJson(log, user);
    }
}
