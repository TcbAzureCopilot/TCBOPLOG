package com.machineroom.service;

import java.util.Collections;
import java.util.List;

/** Business / permission failure carrying an HTTP status and an optional list of detail messages. */
public class ServiceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final List<String> details;

    public ServiceException(int status, String message) {
        this(status, message, null);
    }

    public ServiceException(int status, String message, List<String> details) {
        super(message);
        this.status = status;
        this.details = details == null ? Collections.<String>emptyList() : details;
    }

    public int getStatus() { return status; }
    public List<String> getDetails() { return details; }

    public static ServiceException badRequest(String msg) { return new ServiceException(400, msg); }
    public static ServiceException badRequest(String msg, List<String> details) { return new ServiceException(400, msg, details); }
    public static ServiceException forbidden(String msg) { return new ServiceException(403, msg); }
    public static ServiceException notFound(String msg) { return new ServiceException(404, msg); }
    public static ServiceException conflict(String msg) { return new ServiceException(409, msg); }
}
