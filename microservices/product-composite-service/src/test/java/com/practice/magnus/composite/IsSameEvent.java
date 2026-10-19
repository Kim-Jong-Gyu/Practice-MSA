package com.practice.magnus.composite;

import java.util.Map;
import com.practice.magnus.api.event.Event;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public class IsSameEvent extends TypeSafeMatcher<String> {

    private static final Logger LOG = LoggerFactory.getLogger(IsSameEvent.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final Event<?, ?> expectedEvent;

    private IsSameEvent(Event<?, ?> expectedEvent) {
        this.expectedEvent = expectedEvent;
    }

    @Override
    protected boolean matchesSafely(String eventAsJson) {

        if (expectedEvent == null) return false;

        LOG.trace("Convert the following json string to a map: {}", eventAsJson);
        Map<String, Object> mapEvent = convertJsonStringToMap(eventAsJson);
        mapEvent.remove("eventCreatedAt");

        Map<String, Object> mapExpectedEvent = getMapWithoutCreatedAt(expectedEvent);

        LOG.trace("Got the map: {}", mapEvent);
        LOG.trace("Compare to the expected map: {}", mapExpectedEvent);
        return mapEvent.equals(mapExpectedEvent);
    }

    @Override
    public void describeTo(Description description) {
        String expectedJson = convertObjectToJsonString(expectedEvent);
        description.appendText("expected to look like " + expectedJson);
    }

    public static Matcher<String> sameEventExceptCreatedAt(Event<?, ?> expectedEvent) {
        return new IsSameEvent(expectedEvent);
    }

    private Map<String, Object> getMapWithoutCreatedAt(Event<?, ?> event) {
        Map<String, Object> mapEvent = convertObjectToMap(event);
        mapEvent.remove("eventCreatedAt");
        return mapEvent;
    }

    private Map<String, Object> convertObjectToMap(Object object) {
        return mapper.convertValue(object, MAP_TYPE);
    }

    private String convertObjectToJsonString(Object object) {
        return mapper.writeValueAsString(object);
    }

    private Map<String, Object> convertJsonStringToMap(String eventAsJson) {
        return mapper.readValue(eventAsJson, MAP_TYPE);
    }
}