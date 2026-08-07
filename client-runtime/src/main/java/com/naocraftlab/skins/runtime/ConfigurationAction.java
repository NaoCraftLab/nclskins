package com.naocraftlab.skins.runtime;

import java.util.concurrent.CompletionStage;


@FunctionalInterface
public interface ConfigurationAction {
    CompletionStage<Result> execute();

    record Result(boolean succeeded, String message) {
        public Result {
            message = message == null ? "" : message;
        }

        public static Result succeeded(String message) {
            return new Result(true, message);
        }

        public static Result failed(String message) {
            return new Result(false, message);
        }
    }
}
