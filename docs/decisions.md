# Decisions

Decisions taken on twake-calendar-integration-tests, oldest first. Append only: a decision that changes gets a new entry that replaces the old one and cites it. Entry format: the `twake-decisions` skill of [linagora/twake-guidelines](https://github.com/linagora/twake-guidelines/blob/main/skills/twake-decisions/SKILL.md).

Categories: UX, UI, Product, Architecture, Infra, Tech.

## 2026-11-24, [Product] A resource's participation status is never taken from the organizer

- Decision: the copy of an event delivered to a resource keeps the resource's own PARTSTAT (NEEDS-ACTION on creation) whatever the organizer wrote for it; only the resource side (its administrators, or the side service's auto-accept through the technical token) sets it. The contracts assert NEEDS-ACTION in the resource's copy and in the `resource:calendar:event:created` message when the organizer sends another value.
- Decided by: Benoit Tellier, [linagora/esn-sabre#912](https://github.com/linagora/esn-sabre/issues/912), fixed by [linagora/esn-sabre#913](https://github.com/linagora/esn-sabre/pull/913)
- Why: an organizer who is not an administrator of a room could otherwise book it, or turn an administrator's decline back into an acceptance, by writing PARTSTAT=ACCEPTED for it.
- Scope: CalDAV scheduling of resources (CalDavContract, ResourceAMQPMessageContract).
