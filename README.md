# Integration test suite for Dav servers

Running the tests:

```
mvn clean install
```

## AMQP scheduling (`esn-sabre`):

```
# Default disable: env AMQP_SCHEDULING_ENABLED = false
mvn test

# Enable
mvn test -Pamqp-scheduling-on
```