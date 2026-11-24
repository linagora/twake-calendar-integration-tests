# Decisions

Decisions taken on twake-calendar-integration-tests, oldest first. Append only: a decision that changes gets a new entry that replaces the old one and cites it. Entry format: the `twake-decisions` skill of [linagora/twake-guidelines](https://github.com/linagora/twake-guidelines/blob/main/skills/twake-decisions/SKILL.md).

Categories: UX, UI, Product, Architecture, Infra, Tech.

## 2026-11-24, [Product] A resource's participation status is never taken from the organizer

- Decision: the copy of an event delivered to a resource keeps the resource's own PARTSTAT (NEEDS-ACTION on creation) whatever the organizer wrote for it; only the resource side (its administrators, or the side service's auto-accept through the technical token) sets it. The contracts assert NEEDS-ACTION in the resource's copy and in the `resource:calendar:event:created` message when the organizer sends another value.
- Decided by: Benoit Tellier, [linagora/esn-sabre#912](https://github.com/linagora/esn-sabre/issues/912), fixed by [linagora/esn-sabre#913](https://github.com/linagora/esn-sabre/pull/913)
- Why: an organizer who is not an administrator of a room could otherwise book it, or turn an administrator's decline back into an acceptance, by writing PARTSTAT=ACCEPTED for it.
- Scope: CalDAV scheduling of resources (CalDavContract, ResourceAMQPMessageContract).

## 2026-11-24, [Product] A calendar share request carrying an owner access level answers 400

- Decision: a share request that grants a sharee an owner access level (`<D:shared-owner/>` or `<D:not-shared/>` in an XML `share-resource`) answers 400, whether the owner or a delegated administrator sends it, and creates no calendar instance. A sharee only ever holds read, read-write, administration, free/busy or no access. The contract asserts the 400, the unchanged calendar lists, and that the same route still shares at `<D:read/>`.
- Decided by: Benoit Tellier, [linagora/esn-sabre#911](https://github.com/linagora/esn-sabre/pull/911)
- Why: a sharee instance stored at an owner level acted as a second owner instance: full owner ACL, and deleting it destroyed the whole calendar.
- Scope: CalDAV calendar sharing (CalDavDelegationContract).
