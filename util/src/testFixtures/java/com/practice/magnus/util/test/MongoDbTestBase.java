package com.practice.magnus.util.test;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.mongodb.MongoDBContainer;

public abstract class MongoDbTestBase {

    @ServiceConnection
    protected static final MongoDBContainer mongo = new MongoDBContainer("mongo:8.0")
            .withReuse(true);

    static {
        mongo.start();
    }
}