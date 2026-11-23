package com.occlet.issuance.activities;

import com.occlet.issuance.activities.api.HelloActivities;

public class HelloActivitiesImpl implements HelloActivities {
    @Override
    public String composeGreeting(String name) {
        return "Hello, " + name + "!";
    }
}
