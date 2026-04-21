/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  https://www.gnu.org/licenses/agpl-3.0.en.html                   *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                                                   *
 *                                                                  *
 *  This file was taken and adapted from the Apache James project.  *
 *                                                                  *
 *  https://james.apache.org                                        *
 *                                                                  *
 *  It was originally licensed under the Apache V2 license.         *
 *                                                                  *
 *  http://www.apache.org/licenses/LICENSE-2.0                      *
 ********************************************************************/

package com.linagora.dav;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarParserFactory;
import net.fortuna.ical4j.data.ContentHandlerContext;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.TimeZoneRegistry;
import net.fortuna.ical4j.model.TimeZoneRegistryImpl;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.fortuna.ical4j.util.CompatibilityHints;
import net.fortuna.ical4j.util.MapTimeZoneCache;

public class CalendarUtil {
    public static final String MASTER_RECURRENCE_KEY = "master";

    public static class CustomizedTimeZoneRegistry extends TimeZoneRegistryImpl {

        @Override
        public ZoneId getZoneId(String tzId) {
            try {
                // Attempt to get the zone ID from the parent class first
                return super.getZoneId(tzId);
            } catch (DateTimeException e) {
                // If it fails, we try to get the global zone ID
                if (e.getMessage().contains("Unknown timezone identifier")) {
                    return TimeZoneRegistry.getGlobalZoneId(tzId);
                }
                throw e;
            }
        }
    }

    public static class CalendarExtractor {
        private final Calendar calendar;

        private CalendarExtractor(Calendar calendar) {
            this.calendar = calendar;
        }

        public Property extractProperty(String propertyName) {
            return calendar.getComponent(Component.VEVENT)
                .flatMap(vevent -> vevent.getProperty(propertyName))
                .map(property -> (Property) property)
                .orElseThrow(() -> new AssertionError("Expected VEVENT " + propertyName + " to be present"));
        }

        public String extractPropertyValue(String propertyName) {
            return extractProperty(propertyName).getValue();
        }

        public PartStat extractAttendeePartStat(String attendeeEmail) {
            return CalendarUtil.getAttendeePartStat(calendar, attendeeEmail);
        }

        public Calendar asCalendar() {
            return calendar;
        }
    }

    static {
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_PARSING, true);
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_UNFOLDING, true);
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_VALIDATION, true);
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_OUTLOOK_COMPATIBILITY, true);
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_NOTES_COMPATIBILITY, true);
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_VCARD_COMPATIBILITY, true);

        System.setProperty("net.fortuna.ical4j.timezone.cache.impl", MapTimeZoneCache.class.getName());
    }

    public static CalendarExtractor toExtractor(String icsContent) {
        return toExtractor(icsContent.getBytes(StandardCharsets.UTF_8));
    }

    public static CalendarExtractor toExtractor(byte[] icsContent) {
        return new CalendarExtractor(parseIcs(icsContent));
    }

    public static Calendar parseIcs(String icsContent) {
        return parseIcs(icsContent.getBytes(StandardCharsets.UTF_8));
    }

    public static Calendar parseIcs(byte[] icsContent) {
        CalendarBuilder builder = new CalendarBuilder(
            CalendarParserFactory.getInstance().get(),
            new ContentHandlerContext().withSupressInvalidProperties(true),
            new CustomizedTimeZoneRegistry());
        try {
            return builder.build(new ByteArrayInputStream(icsContent));
        } catch (IOException | ParserException e) {
            throw new RuntimeException("Error while parsing calendar", e);
        }
    }

    public static Calendar parseIcsAndSanitize(String icsContent) {
        return parseIcsAndSanitize(icsContent, Property.PRODID, Property.DTSTAMP);
    }

    public static Calendar parseIcsAndSanitize(String icsContent, String... ignoredProperties) {
        Calendar calendar = parseIcs(icsContent);
        removeAllProperties(calendar, ignoredProperties);
        return calendar;
    }

    public static void removeAllProperties(Calendar calendar, String... propertyNames) {
        for (String name : propertyNames) {
            calendar.removeAll(name);
            calendar.getComponents().forEach(c -> c.removeAll(name));
        }
    }

    public static void removeParticipantScheduleStatus(Calendar calendar) {
        calendar.getComponents(Component.VEVENT).forEach(vevent -> {
            vevent.getProperties(Property.ATTENDEE)
                .forEach(attendee -> removeParameter(attendee, Parameter.SCHEDULE_STATUS));
            vevent.getProperties(Property.ORGANIZER)
                .forEach(organizer -> removeParameter(organizer, Parameter.SCHEDULE_STATUS));
        });
    }

    public static PartStat getAttendeePartStat(String icsContent, String attendeeEmail) {
        return getAttendeePartStat(parseIcs(icsContent), attendeeEmail);
    }

    public static PartStat getAttendeePartStat(Calendar calendar, String attendeeEmail) {
        return findAttendeeProperties(calendar, attendeeEmail)
            .findFirst()
            .map(p -> partStatOf(p, attendeeEmail))
            .orElseThrow(() -> new AssertionError("Attendee not found in calendar: " + attendeeEmail));
    }

    public static Map<String, PartStat> getRecurringAttendeePartStats(String icsContent, String attendeeEmail) {
        return getRecurringAttendeePartStats(parseIcs(icsContent), attendeeEmail);
    }

    public static Map<String, PartStat> getRecurringAttendeePartStats(Calendar calendar, String attendeeEmail) {
        String mailAddress = "mailto:" + attendeeEmail;
        Map<String, PartStat> result = new LinkedHashMap<>();
        for (Component vevent : calendar.getComponents(Component.VEVENT)) {
            vevent.getProperties(Property.ATTENDEE).stream()
                .filter(p -> mailAddress.equalsIgnoreCase(p.getValue()))
                .findFirst()
                .ifPresent(p -> {
                    String key = vevent.getProperty(Property.RECURRENCE_ID)
                        .map(Property::getValue)
                        .orElse(MASTER_RECURRENCE_KEY);
                    result.put(key, partStatOf(p, attendeeEmail));
                });
        }
        if (result.isEmpty()) {
            throw new AssertionError("Attendee not found in calendar: " + attendeeEmail);
        }
        return result;
    }

    public static String withAttendeePartStat(String icsContent, String attendeeEmail, PartStat partStat) {
        Calendar calendar = parseIcs(icsContent);
        setAttendeePartStat(calendar, attendeeEmail, partStat);
        return calendar.toString();
    }

    public static void setAttendeePartStat(Calendar calendar, String attendeeEmail, PartStat partStat) {
        List<Property> attendees = findAttendeeProperties(calendar, attendeeEmail).toList();
        if (attendees.isEmpty()) {
            throw new AssertionError("Attendee not found in calendar: " + attendeeEmail);
        }
        attendees.forEach(p -> {
            removeParameter(p, Parameter.PARTSTAT);
            p.add(partStat);
        });
    }

    public static void removeAttendeePartStat(Calendar calendar, String attendeeEmail) {
        findAttendeeProperties(calendar, attendeeEmail)
            .forEach(attendee -> removeParameter(attendee, Parameter.PARTSTAT));
    }

    private static Stream<Property> findAttendeeProperties(Calendar calendar, String attendeeEmail) {
        String mailAddress = "mailto:" + attendeeEmail;
        return calendar.getComponents(Component.VEVENT).stream()
            .flatMap(v -> v.getProperties(Property.ATTENDEE).stream())
            .filter(p -> mailAddress.equalsIgnoreCase(p.getValue()));
    }

    private static PartStat partStatOf(Property attendee, String email) {
        return attendee.getParameter(Parameter.PARTSTAT)
            .map(p -> (PartStat) p)
            .orElseThrow(() -> new AssertionError("Missing PARTSTAT for attendee " + email));
    }

    private static void removeParameter(Property property, String parameterName) {
        property.getParameter(parameterName).ifPresent(property::remove);
    }
}