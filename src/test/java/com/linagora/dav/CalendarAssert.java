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
 ********************************************************************/
package com.linagora.dav;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.assertj.core.api.AbstractAssert;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyList;

public class CalendarAssert extends AbstractAssert<CalendarAssert, Calendar> {

    private final List<UnaryOperator<Calendar>> transformations = new ArrayList<>();

    private CalendarAssert(Calendar actual) {
        super(actual, CalendarAssert.class);
    }

    public static CalendarAssert assertThatCalendar(Calendar actual) {
        return new CalendarAssert(actual);
    }

    public static CalendarAssert assertThatCalendar(String ics) {
        return new CalendarAssert(CalendarUtil.parseIcsAndSanitize(ics));
    }

    public CalendarAssert ignoringParticipantScheduleStatus() {
        transformations.add(calendar -> {
            Calendar copy = calendar.copy();
            CalendarUtil.removeParticipantScheduleStatus(copy);
            return copy;
        });
        return this;
    }

    public CalendarAssert ignoringProperties(String... propertyNames) {
        transformations.add(calendar -> removeProperties(calendar, propertyNames));
        return this;
    }

    public CalendarAssert ignoringAttendeePartStat(String attendeeEmail) {
        transformations.add(calendar -> {
            Calendar copy = calendar.copy();
            CalendarUtil.removeAttendeePartStat(copy, attendeeEmail);
            return copy;
        });
        return this;
    }

    @Override
    public CalendarAssert isEqualTo(Object expected) {
        if (expected instanceof Calendar) {
            return isEqualToCalendar((Calendar) expected);
        }
        if (expected instanceof String) {
            return isEqualToCalendar(CalendarUtil.parseIcsAndSanitize((String) expected));
        }
        return super.isEqualTo(expected);
    }

    private CalendarAssert isEqualToCalendar(Calendar expected) {
        isNotNull();
        Calendar transformedActual = applyTransformations(actual);
        Calendar transformedExpected = applyTransformations(expected);
        assertNormalizedEquals(transformedActual, transformedExpected);
        return this;
    }

    private Calendar applyTransformations(Calendar calendar) {
        Calendar result = calendar.copy();
        for (UnaryOperator<Calendar> transformation : transformations) {
            result = transformation.apply(result);
        }
        return result;
    }

    private void assertNormalizedEquals(Calendar actual, Calendar expected) {
        Calendar sortedActual = reSortProperties(actual);
        Calendar sortedExpected = reSortProperties(expected);

        if (!sortedActual.equals(sortedExpected)) {
            failWithMessage("Expected calendar:%n%s%nbut was:%n%s", sortedExpected, sortedActual);
        }
    }

    private static Calendar reSortProperties(Calendar calendar) {
        List<Property> sortedCalProps = calendar.getProperties().stream()
            .sorted(Comparator.comparing(Property::toString))
            .collect(Collectors.toList());
        calendar.setPropertyList(new PropertyList(sortedCalProps));

        calendar.getComponents().forEach(component -> {
            List<Property> sortedProps = component.getProperties().stream()
                .sorted(Comparator.comparing(Property::toString))
                .collect(Collectors.toList());
            component.setPropertyList(new PropertyList(sortedProps));
        });

        return calendar;
    }

    private static Calendar removeProperties(Calendar calendar, String... propertyNames) {
        Calendar copy = calendar.copy();
        CalendarUtil.removeAllProperties(copy, propertyNames);
        return copy;
    }
}