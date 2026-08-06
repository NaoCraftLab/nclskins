package com.naocraftlab.skins.runtime;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;


final class SqliteSupport {
    private static volatile Boolean available;

    private SqliteSupport() {
    }

    static boolean available() {
        Boolean observed = available;
        if (observed != null) {
            return observed;
        }
        synchronized (SqliteSupport.class) {
            if (available == null) {
                available = probe();
            }
            return available;
        }
    }

    private static boolean probe() {
        try {
            Class.forName("org.sqlite.JDBC", true, SqliteSupport.class.getClassLoader());
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only=ON");
                try (ResultSet result = statement.executeQuery("SELECT 1")) {
                    return result.next() && result.getInt(1) == 1;
                }
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException | java.sql.SQLException unavailable) {
            return false;
        }
    }
}
