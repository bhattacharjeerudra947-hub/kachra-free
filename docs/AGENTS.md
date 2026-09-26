# AGENTS.md — Kachra Free Project Context

## Project Overview

Kachra Free is a garbage-collection tracking and ETA system designed around a simple operational problem:

Residents often do not know when the garbage collection truck will reach their house. Collection timings can vary, routes can change, trucks can be delayed, and a resident may miss collection if they do not know when the truck is approaching.

The system provides residents with the expected location and arrival time of the garbage truck serving their area.

The system consists of three major parts:

1. **Driver-side application**
2. **User/resident-side application**
3. **Central server + administration panel**

The system should remain deliberately simple and close to a handmade engineering implementation. Avoid unnecessary abstraction, infrastructure, dependencies, or over-engineering. The goal is to build a working prototype whose important behaviour is understandable and demonstrable.

---

# 1. Driver-Side Application

## Purpose

The driver-side application exists primarily to provide the server with the current location of a garbage collection truck.

The driver should not need to interact with the application during normal operation.

The application should therefore be extremely minimal.

## Driver registration

Each truck has a unique **Truck ID**.

The driver application should allow the operator to associate the device/application with a Truck ID during setup.

Conceptually:

```text
Open driver application
        ↓
Enter/select Truck ID
        ↓
Register device as that truck
        ↓
Start location sharing
```

After registration, the application should operate automatically.

## Background location tracking

The driver application should:

* run in the background during collection
* continuously obtain the truck's location
* periodically send the current location to the server
* continue sending updates without requiring the driver to manually interact with the application
* associate every location update with the registered Truck ID

The important information conceptually is:

```text
Truck ID
Current location
Time of location update
```

The exact update frequency should be determined during implementation based on battery usage, accuracy, network availability, and the requirements of the prototype.

## Driver UX

Keep the driver interface minimal.

The driver should primarily be able to see:

* registered Truck ID
* whether location sharing is active
* whether the application is connected to the server
* possibly the last successful location update

There should not be unnecessary driver-facing functionality.

The driver should not need to enter the route manually during normal operation.

---

# 2. User / Resident Application

## Purpose

The resident application allows a user to register their house location and receive information about the garbage truck serving their house.

The resident does **not** need to continuously share their live location.

Their house location is treated as a relatively static location.

## Initial registration

During first-time setup, the user provides:

* phone number
* house/home location
* required notification preferences
* preferred ETA alert range

The house location should be saved by the system.

The user may later edit their registered house location.

Conceptually:

```text
User opens application
        ↓
Registers phone number
        ↓
Selects house location
        ↓
Selects preferred alert range
        ↓
Registration complete
```

The system should not continuously track the resident's location after registration.

## House location

The resident's registered location represents the destination to which the garbage truck is expected to travel.

The location can be edited later if the resident moves or the original location was incorrect.

The system should distinguish between:

* registered house location
* current truck location

The resident's location should not be treated as a continuously changing GPS position.

---

# 3. ETA Alert Preferences

The resident should be able to choose how early they want to be alerted.

For example:

```text
Alert me when truck is approximately:

[ 5 minutes away ]
[ 10 minutes away ]
[ 15 minutes away ]
[ 30 minutes away ]
```

The exact available choices can be decided during implementation.

The important concept is that the user controls the alert threshold.

When the server determines that the truck's predicted arrival time has entered the user's selected alert range, the system should trigger an alert.

For example:

```text
User preference:
Alert when ETA <= 10 minutes

Current predicted ETA:
8 minutes

→ Send alert
```

The system should avoid repeatedly sending the same alert for the same collection event.

---

# 4. Smartphone and Non-Smartphone Support

The system should not assume that every resident has a smartphone.

Registration requires a phone number so that residents can potentially receive information through more than one channel.

The system should support:

### Smartphone users

The application can provide:

* push/application alerts
* truck location
* ETA
* map
* expected route/path
* other relevant collection information

### Non-smartphone users

The system should be able to send an SMS/text message to the registered phone number.

For example:

```text
Garbage truck is approximately 8 minutes away.
```

The exact message format can be decided later.

The important design principle is:

> A resident should still be able to receive the essential garbage-collection alert without owning or actively using a smartphone.

---

# 5. User-Facing Truck Map

The resident application should provide a map showing nearby garbage trucks relevant to the user's registered house.

The user should be able to see:

* their registered house location
* nearby relevant garbage truck(s)
* current truck position
* expected path toward their house
* ETA

The map is intended to make the system understandable at a glance.

Conceptually:

```text
                Truck
                  🚛
                  │
                  │ current route
                  │
                  ↓
              ────────
                  │
                  │
                  ↓
              🏠 House

              ETA: 11 min
```

The system should not simply draw a straight line between the truck and the house.

The displayed path should represent the route that the truck is expected to take.

---

# 6. Route Prediction

This is one of the most important parts of the system.

The garbage truck should **not necessarily take the shortest route to the user's house**.

A garbage collection truck follows an operational collection route.

The route depends on the historical order in which houses/areas are normally serviced.

Therefore, the server must consider the historical collection sequence when determining the expected path.

Conceptually:

```text
Historical collection order

House A
   ↓
House B
   ↓
House C
   ↓
House D
   ↓
House E
```

If the truck is currently at House B and the user is House E, the expected route is based on the collection sequence:

```text
Truck
  ↓
C
  ↓
D
  ↓
E
  ↓
User
```

rather than simply asking:

> "What is the shortest road route from the truck to the user's house?"

The historical collection order is therefore a core part of the ETA system.

---

# 7. Server Responsibilities

The central server is the main coordination and computation component.

It should:

1. receive driver location updates
2. maintain the current state/location of trucks
3. associate trucks with their Truck IDs
4. maintain registered residents and their house locations
5. maintain historical collection information
6. determine which truck/route is relevant to a resident
7. determine the expected collection path
8. incorporate traffic/travel-time information
9. calculate the predicted ETA
10. provide the ETA and truck information to the user application
11. trigger user notifications when their alert threshold is reached
12. support SMS/text notifications where applicable
13. provide administrative functionality

---

# 8. ETA Calculation

ETA is not simply:

```text
distance / average speed
```

The ETA should consider the actual collection process.

The server should combine information such as:

```text
Current truck location
        +
Resident house location
        +
Historical collection order
        +
Expected collection path
        +
Traffic/travel conditions
        +
Historical timing information
        ↓
Predicted ETA
```

The system should use the historical collection order to determine **which route the truck is expected to follow**.

Traffic information should then help estimate how long travel along the relevant route will take.

Historical collection timing can provide additional information about how the collection process normally behaves.

The resulting ETA should represent:

> the estimated time until the truck reaches the resident's house, not merely the estimated driving time between two geographic points.

This distinction is important because garbage collection involves stops, route order, and operational delays.

---

# 9. Historical Collection Data

Historical collection information is a core input to the system.

The historical information should allow the server to learn or determine things such as:

* order in which houses/areas are normally visited
* typical timing of collection
* route progression
* expected time between collection points
* historical deviations/delays where useful

The historical data should therefore represent the actual collection process rather than merely generic road distances.

The system should be designed so that historical collection behaviour can improve ETA estimation.

---

# 10. Traffic Information

Traffic information is another input to ETA calculation.

The server should use current or otherwise available traffic/travel information to account for changes in road travel time.

Conceptually:

```text
Historical route
       +
Current truck position
       +
Traffic conditions
       ↓
Expected travel time
```

Traffic should affect ETA rather than being displayed merely as unrelated information.

---

# 11. Nearby Truck Selection

A resident may potentially have multiple trucks nearby.

The server should determine which truck is relevant to that resident based on the collection route and operational context.

The system should not blindly select:

```text
nearest truck geographically
```

if that truck does not serve the resident's collection route.

The relevant truck should be determined using the collection system and route information.

---

# 12. Notification Flow

The intended notification flow is:

```text
Driver application
        ↓
Current truck location
        ↓
Server
        ↓
Route + traffic + historical data
        ↓
ETA calculation
        ↓
Resident's alert threshold checked
        ↓
ETA enters alert range
        ↓
Notification
   ┌────┴────┐
   ↓         ↓
App alert   SMS
```

For example:

```text
Resident preference:
Alert at ≤ 10 minutes

Server:
Predicted ETA = 13 minutes

→ No alert

Later:

Predicted ETA = 9 minutes

→ Send alert
```

The notification should be associated with the relevant collection event so that the user does not receive repeated alerts every time the server updates the ETA.

---

# 13. Administrative Panel

The server should also provide an admin panel.

The admin panel exists for operational management and for residents who cannot complete registration themselves.

## Manual resident registration

An administrator should be able to register a resident manually.

This is particularly important for users without smartphones.

The administrator should be able to enter/register information such as:

* resident phone number
* house location
* relevant collection/route information
* alert preference
* other necessary registration information

After registration, the resident should be part of the same backend system as smartphone users.

The important principle is:

> Smartphone registration and administrator-assisted registration should ultimately create the same kind of resident record.

---

# 14. Administrative Functions

The admin panel should provide the minimum functionality required to operate and demonstrate the system.

Potential responsibilities include:

* register trucks
* associate trucks with Truck IDs
* register residents
* edit resident information
* edit house locations
* configure collection routes/order
* inspect active trucks
* inspect truck locations
* inspect registered users
* inspect collection information
* manually assist users who cannot register themselves
* monitor whether trucks are currently sending location updates

Do not add administrative functionality that is not required by the core system.

---

# 15. Core System Flow

The complete intended system behaviour is:

```text
                     DRIVER
                       │
                       │ GPS updates
                       ↓
                  ┌──────────┐
                  │  SERVER  │
                  └────┬─────┘
                       │
          ┌────────────┼─────────────┐
          │            │             │
          ↓            ↓             ↓
     Truck state   Historical     Traffic /
                    collection    travel data
                    sequence
          │            │             │
          └────────────┼─────────────┘
                       ↓
                 Route prediction
                       ↓
                   ETA engine
                       ↓
              Relevant residents
                       │
             ┌─────────┴─────────┐
             ↓                   ↓
       Smartphone app           SMS
             │
             ↓
      Map + truck position
      + expected path
      + ETA
      + alert
```

---

# 16. Important Design Principles

## 16.1 Do not continuously track residents

The resident's house location is registered once and can be edited later.

The system does not need the resident's live GPS location during normal operation.

This reduces unnecessary tracking and simplifies the system.

---

## 16.2 The truck is continuously tracked

The truck is the moving object.

The driver application continuously supplies its location to the server.

---

## 16.3 ETA is route-aware

Do not treat ETA as a simple point-to-point navigation problem.

The system must account for the truck's expected collection sequence.

---

## 16.4 Historical collection behaviour matters

The system should use historical collection order/timing as part of determining the expected route and ETA.

The truck's operational route is more important than simply finding the geographically shortest path.

---

## 16.5 Notifications are threshold-based

Residents choose how early they want to be alerted.

The server determines when the predicted ETA crosses that threshold.

---

## 16.6 Smartphone is optional for receiving alerts

The core service should remain usable through SMS for residents without smartphones.

---

## 16.7 Keep the driver experience extremely simple

The driver's primary job is to allow the system to receive location updates.

The driver should not have to continuously operate the application.

---

## 16.8 Keep the system understandable

This is a prototype/student engineering project.

Prefer simple, explicit implementations over unnecessary layers of abstraction.

Every major part of the system should be understandable by the team and demonstrable during evaluation.

---

# 17. Prototype Priority

Implementation should be prioritized in this order:

### Phase 1 — Basic truck tracking

```text
Driver app
    ↓
Truck location
    ↓
Server
    ↓
Current truck position
```

### Phase 2 — Resident registration

```text
Resident
    ↓
Phone + house location
    ↓
Server
```

### Phase 3 — Route representation

```text
Historical collection order
        ↓
Expected truck route
```

### Phase 4 — ETA

```text
Truck position
+
Resident location
+
Expected collection route
+
Traffic
+
Historical timing
        ↓
ETA
```

### Phase 5 — Resident interface

```text
Map
Truck position
Expected path
ETA
```

### Phase 6 — Notifications

```text
ETA crosses user's threshold
        ↓
App notification
and/or
SMS
```

### Phase 7 — Admin functionality

```text
Admin
 ↓
Register/edit residents
Register/manage trucks
Manage collection information
Monitor system
```

The prototype should have a complete end-to-end path before additional features are added.

---

# 18. End-to-End Definition of Done

The core system is considered functional when the following scenario works:

```text
1. A truck is registered with a Truck ID.

2. The driver's application is running.

3. The driver application sends the truck's current
   location to the server.

4. A resident is registered with a phone number
   and house location.

5. The server knows the historical collection order
   relevant to the resident.

6. The server receives the current truck location.

7. The server determines the expected collection path
   toward the resident.

8. Traffic/travel information is incorporated.

9. The server calculates an ETA.

10. The resident can see the relevant truck,
    its expected path and ETA.

11. When the ETA enters the resident's configured
    alert range, the resident receives an alert.

12. A resident without a smartphone can still receive
    the essential alert through SMS.

13. An administrator can manually register a resident
    who cannot use the application.
```

The final demonstration should therefore show a **real end-to-end flow**, rather than isolated screens or manually calculated ETAs.

---

# 19. What the System Ultimately Provides

The final user-facing promise of Kachra Free is:

> **Know when your garbage truck is coming.**

The system achieves this by continuously tracking the garbage truck, understanding the truck's normal collection route, accounting for traffic and historical collection behaviour, calculating an ETA for each relevant resident, and delivering that information through the resident application or SMS.

