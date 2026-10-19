package com.practice.magnus.util.test;// util/src/testFixtures/java/com/practice/magnus/util/test/test.MySqlTestBase.java

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.mysql.MySQLContainer;

public abstract class MySqlTestBase {

    @ServiceConnection
    protected static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("review-db")
            .withReuse(true);

    static {
        mysql.start();
    }
}