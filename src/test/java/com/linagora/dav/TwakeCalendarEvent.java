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

import java.net.URI;
import java.util.List;
import java.util.Optional;

import com.google.common.collect.ImmutableList;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.ParameterList;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.Cn;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.fortuna.ical4j.model.parameter.TzId;
import net.fortuna.ical4j.model.property.Action;
import net.fortuna.ical4j.model.property.Attendee;
import net.fortuna.ical4j.model.property.CalScale;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.ExDate;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.Organizer;
import net.fortuna.ical4j.model.property.RRule;
import net.fortuna.ical4j.model.property.RecurrenceId;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Transp;
import net.fortuna.ical4j.model.property.Trigger;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.Version;

public record TwakeCalendarEvent(Calendar calendar) {

    public static final TzId VN_TIMEZONE = new TzId("Asia/Ho_Chi_Minh");

    public static class Builder {
        private Optional<String> uid = Optional.empty();
        private Optional<String> summary = Optional.empty();
        private Optional<String> location = Optional.empty();
        private Optional<String> description = Optional.empty();
        private Optional<String> dtstart = Optional.empty();
        private Optional<String> dtend = Optional.empty();
        private Optional<String> transparent = Optional.empty();
        private Optional<String> rrule = Optional.empty();
        private Optional<String> exDate = Optional.empty();
        private Optional<Organizer> organizer = Optional.empty();
        private Optional<String> recurrenceId = Optional.empty();
        private Optional<String> overrideStart = Optional.empty();
        private Optional<String> overrideEnd = Optional.empty();
        private Optional<String> alarmTrigger = Optional.empty();

        private final ImmutableList.Builder<Attendee> attendees = new ImmutableList.Builder<>();

        public Builder uid(String uid) {
            this.uid = Optional.of(uid);
            return this;
        }

        public Builder summary(String summary) {
            this.summary = Optional.of(summary);
            return this;
        }

        public Builder location(String location) {
            this.location = Optional.of(location);
            return this;
        }

        public Builder description(String description) {
            this.description = Optional.of(description);
            return this;
        }

        public Builder dtstart(String dtstart) {
            this.dtstart = Optional.of(dtstart);
            return this;
        }

        public Builder dtend(String dtend) {
            this.dtend = Optional.of(dtend);
            return this;
        }

        public Builder transparent(String transparent) {
            this.transparent = Optional.of(transparent);
            return this;
        }

        public Builder rrule(String rrule) {
            this.rrule = Optional.of(rrule);
            return this;
        }

        public Builder organizer(String email) {
            Organizer org = new Organizer(URI.create("mailto:" + email));
            org.add(new Cn(email));
            this.organizer = Optional.of(org);
            return this;
        }

        public Builder attendee(String email) {
            Attendee attendee = new Attendee(URI.create("mailto:" + email));
            attendee.add(new Cn(email));
            attendee.add(new PartStat("NEEDS-ACTION"));
            this.attendees.add(attendee);
            return this;
        }

        public Builder exDate(String exdate) {
            this.exDate = Optional.of(exdate);
            return this;
        }

        public Builder recurrenceOverride(String recurrenceId, String newStart, String newEnd) {
            this.recurrenceId = Optional.of(recurrenceId);
            this.overrideStart = Optional.of(newStart);
            this.overrideEnd = Optional.of(newEnd);
            return this;
        }

        public Builder alarmTrigger(String alarmTrigger) {
            this.alarmTrigger = Optional.of(alarmTrigger);
            return this;
        }

        public TwakeCalendarEvent build() {
            if (uid.isEmpty() || dtstart.isEmpty() || dtend.isEmpty() || organizer.isEmpty()) {
                throw new IllegalStateException("uid, dtstart, dtend, organizer are mandatory");
            }

            Calendar calendar = new Calendar();
            calendar.add(new Version(new ParameterList(List.of()), Version.VALUE_2_0));
            calendar.add(new CalScale("GREGORIAN"));
            VEvent event = createBasicEvent();
            rrule.ifPresent(rule -> event.add(new RRule(rule)));
            exDate.ifPresent(date -> event.add(new ExDate(new ParameterList(List.of(VN_TIMEZONE)), date)));
            calendar.add(event);

            recurrenceId.ifPresent(id -> {
                VEvent subEvent = createBasicEvent();
                subEvent.add(new RecurrenceId(new ParameterList(List.of(VN_TIMEZONE)), id));
                overrideStart.ifPresent(date -> subEvent.replace(new DtStart(new ParameterList(List.of(VN_TIMEZONE)), date)));
                overrideEnd.ifPresent(date -> subEvent.replace(new DtEnd(new ParameterList(List.of(VN_TIMEZONE)), date)));
                calendar.add(subEvent);
            });

            return new TwakeCalendarEvent(calendar);
        }

        private VEvent createBasicEvent() {
            VEvent event = new VEvent();
            event.add(new Uid(uid.get()));
            event.add(new DtStart(new ParameterList(List.of(VN_TIMEZONE)), dtstart.get()));
            event.add(new DtEnd(new ParameterList(List.of(VN_TIMEZONE)), dtend.get()));
            summary.ifPresent(s -> event.add(new Summary(s)));
            location.ifPresent(l -> event.add(new Location(l)));
            description.ifPresent(d -> event.add(new Description(d)));
            organizer.ifPresent(o -> event.add(o));
            attendees.build().forEach(event::add);
            transparent.ifPresent(t -> event.add(new Transp(t)));

            alarmTrigger.ifPresent(trigger -> {
                VAlarm alarm = new VAlarm();
                alarm.add(new Attendee(organizer.get().getValue()));
                alarm.add(new Action("EMAIL"));
                alarm.add(new Summary("Test alarm"));
                alarm.add(new Description("Event reminder"));
                alarm.add(new Trigger(new ParameterList(List.of()), trigger));
                event.add(alarm);
            });

            return event;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return calendar.toString();
    }
}
