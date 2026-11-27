# Multi-Country Multi-Tenant Education, Community & Tutoring Platform
*Updated working specification — 20 November 2026. This revision preserves the original multi-country, multi-tenant architecture and adds product requirements for student portfolios, school-wide communications, SIS imports, teacher tools, curriculum alignment, tutor insights, AI Tutor Copilot, student-facing AI tutoring, and strengthened safeguarding. The revised phase descriptions in Sections 112–117 and the additions in Sections 123–133 form the updated working specification.*

> **Read with §135 (26 November 2026).** The product scope was revised: there are **no online courses, recorded or hosted video, tutor bookings, sessions, reviews, payouts or commissions, and no Student AI Tutor or Tutor Copilot**; the tutor marketplace is a **paid tutor directory** with platform messaging; the school AI assistant is deferred; attendance is manual and QR; transfer is a status. Older sections still describe the original scope in places; **wherever they mention the removed items, §134 and §135 apply**, and the sections with normative rules about them carry a banner.

## 1. Product Overview
The platform is a **mobile-first, multi-country, multi-tenant education ecosystem** connecting schools, teachers, students, parents/guardians, and independent tutors.

It combines:

- School management

- ClassDojo-style student engagement

- Classroom communication

- Parent–teacher communication

- Class feeds and stories

- Attendance

- Assignments and assessment

- School digital library

- Online learning

- Pre-recorded courses

- Live tutoring courses

- Hybrid courses

- Personal tutor marketplace

- Tutor bookings

- Video tutoring

- Payments

- Calendar/events

- Multilingual communication

- Multi-country localization

Additional capabilities:

- Student portfolios and evidence of learning

- School-wide announcements and family communication

- Student and staff roster import and future SIS integration

- Configurable curriculum and learning standards

- AI Tutor Copilot for human tutors and teachers

- Student-facing AI learning tutor

- Consent, safeguarding and AI governance

The product should initially launch in Ethiopia but must be architected so that other African countries can be added through **configuration rather than separate applications or codebases**.

# 2. Product Vision
The long-term product is not simply a school ERP and not simply a tutoring marketplace.

It is a unified education ecosystem:

PLATFORM

│

┌───────────────────┼───────────────────┐

│ │ │

SCHOOLS LEARNING MARKETPLACE

│ │ │

Classes Courses Tutors

Teachers Materials Services

Students Assessments Bookings

Parents Progress Video

Attendance Library Payments

Points

Feed

Chat

The same user account can participate in multiple parts of the ecosystem.

Example:

Teacher

├── Teaches Grade 8 at School A

├── Creates a school revision course

└── Has an optional marketplace tutor profile

Parent

├── Parent of Student A

├── Communicates with teachers

├── Views child's class content

└── Books a private tutor

Student

├── Member of School Class A

├── Enrolled in Course B

├── Uses school library

└── Finds a tutor

# 3. Core Architectural Principle
The platform should have **one global application and one core backend**, with country and tenant configuration.

Do not create:

Ethiopia App

Kenya App

Uganda App

Rwanda App

Instead:

ONE PLATFORM

│

┌──────────────┼──────────────┐

│ │ │

Ethiopia Kenya Rwanda

│ │ │

Schools Schools Schools

Country-specific differences are configuration/data.

Examples:

- Language

- Currency

- Calendar

- Education structure

- Grading system

- Payment providers

- Holidays

- Address structure

- Phone formatting

- Country-specific rules

# 4. Major Product Domains

> **Revised (26 November 2026, §135). Courses, live and recorded learning, tutoring sessions, marketplace payouts and the AI tutoring domains are removed; the tutor domain is a directory.**
The platform should be divided into the following domains:

01\. Identity & Authentication

02\. Tenancy & Organizations

03\. Country & Localization

04\. School Management

05\. Academic Management

06\. People & Relationships

07\. Classroom

08\. Student Engagement

09\. Assignments & Assessment

10\. Communication

11\. Class Feed & Stories

12\. Digital Library

13\. Learning & Tutoring Marketplace

14\. Attendance

15\. Payments & Finance

16\. Calendar & Events

17\. Media

18\. Notifications

19\. Moderation & Safety

20\. Reporting & Analytics

21\. Subscriptions

22\. Platform Administration

23\. Curriculum & Learning Standards

24\. Student Portfolios

25\. School Communications & SIS Integration

26\. AI Tutoring & Tutor Copilot

27\. Student AI Learning Tutor

28\. Consent, Safeguarding & AI Governance

# 5. Tenant Hierarchy
Country is not the tenant.

A recommended hierarchy is:

Platform

│

├── Country

│ │

│ ├── Organization

│ │ ├── School

│ │ │ ├── Campus

│ │ │ └── ...

│ │ └── School

│ │

│ └── Independent School

│

└── Country

## Example
Ethiopia

│

├── Sunrise Education Group

│ ├── Addis Campus

│ ├── Hawassa Campus

│ └── Bahir Dar Campus

│

└── Independent Academy

A school can operate independently without an organization.

A larger education group can own multiple schools.

# 6. Global User Identity
Use a single global user identity.

Do not create unrelated accounts for every role.

User

│

├── Student Profile

├── Parent/Guardian Profile

├── Teacher Profile

└── Tutor Profile

A user can have multiple roles.

Example:

User

├── Teacher at School A

└── Tutor in Marketplace

The important distinction is between:

- **Identity**

- **Role**

- **Membership**

- **Relationship**

- **Permissions**

# 7. User Types
PLATFORM_ADMIN

COUNTRY_ADMIN

ORGANIZATION_ADMIN

SCHOOL_ADMIN

CAMPUS_ADMIN

TEACHER

TEACHER_ASSISTANT

PARENT

GUARDIAN

STUDENT

TUTOR

Roles are not the same as access.

For example:

> Being a teacher does not mean the user can see every student in the school.

# 8. Membership and Relationship Model
The platform should explicitly model where and why a user has access.

Examples:

Teacher

│

└── teaches

│

└── Class 8A

Student

│

└── enrolled in

│

└── Class 8A

Parent

│

└── guardian of

│

└── Student A

This relationship model is the foundation for authorization.

# 9. Classroom Access Rule
This is a central requirement.

## Student
A student can see content only from:

Classes the student is currently enrolled in

## Teacher
A teacher can see content only from:

Classes the teacher is assigned to teach

## Parent/Guardian
A parent/guardian can see:

Class content belonging to classes in which their child is enrolled

They should not automatically see every class in the school.

# 10. Class Membership
Use explicit membership tables.

class_students

---------------

id

class_id

student_id

status

joined_at

left_at

class_teachers

--------------

id

class_id

teacher_id

role

joined_at

left_at

Do not assume class membership from a student's current grade alone.

# 11. Parent Access to Class Content
A parent does not need to be a direct member of every class.

Their access is derived from:

Parent

↓

Guardian relationship

↓

Child

↓

Class membership

↓

Class content

Example:

Parent A

│

├── Child 1 → Grade 5A

│

└── Child 2 → Grade 2B

Parent A sees:

Grade 5A content

Grade 2B content

Parent A does not see:

Grade 6A

Grade 7B

unless another authorized relationship exists.

# 12. Historical Class Access
Class membership should contain dates/status.

This allows the system to distinguish:

CURRENT

FORMER

PENDING

REMOVED

If a student moves from Grade 5A to Grade 5B:

Grade 5A

membership ends

Grade 5B

membership starts

Historical records remain available according to school policy, but current class content does not automatically follow the student forever.

# 13. School Management Module
Each school can manage:

- Basic information

- Branding

- Campuses

- Administrators

- Teachers

- Students

- Guardians

- Academic years

- Academic periods

- Grades

- Classes

- Subjects

- School settings

- Enabled features

- Languages

- Subscription

# 14. Academic Structure
Do not hard-code the academic structure.

Use:

Academic Year

│

├── Academic Periods

├── Grade Levels

├── Classes

└── Subjects

An academic year may contain:

Term 1

Term 2

Term 3

or:

Semester 1

Semester 2

or another configuration.

# 15. School Class
A school class is the official school relationship.

Example:

Grade 8A

├── Teacher

├── Students

├── Subjects

├── Attendance

├── Assignments

├── Grades

├── Feed

├── Stories

├── Points

├── Events

└── Class communication

A school class is **not the same thing as an online course or tutoring service**.

# 16. Classroom Feed
Each class has its own feed.

Grade 5A

│

├── Posts

├── Stories

├── Announcements

├── Photos

├── Videos

├── Achievements

└── Activities

Every post must belong to a specific scope.

Example:

post

----

scope_type = CLASS

scope_id = Grade5A

The backend checks whether the current user has access to that class.

# 17. Class Stories
Stories are temporary content.

story

-----

id

class_id

author_id

media_id

caption

expires_at

status

Typical lifetime:

24 hours

Stories remain scoped to the class.

# 18. Parent Photo and Video Contributions
Parents can contribute photos/videos, but access must be controlled.

Recommended workflow:

Parent

↓

Create contribution

↓

Select child/class context

↓

Upload photo/video

↓

Moderation / approval

↓

Publish to class feed or story

School settings should control:

Parent contributions = ON/OFF

Approval required = YES/NO

Allowed media = PHOTO/VIDEO

Allowed scope = CLASS/SCHOOL

Parents should not automatically have unrestricted access to school-wide publishing.

# 19. Student Engagement / Points
This module provides the ClassDojo-style experience.

Teachers can award points.

Parents can award points to their children.

Example:

Teacher

→ Student

→ Great Work

→ +2

Parent

→ Child

→ Homework

→ +1

# 20. Point Categories
Schools can define categories such as:

Great Work

Hard Working

Kindness

Participation

Helping Others

Problem Solving

Reading

Excellent Attendance

Achievement

Each school can enable, rename, or create categories.

# 21. Point Transactions
Do not store only one mutable point total.

Use a transaction ledger:

point_transactions

------------------

id

student_id

awarded_by_user_id

category_id

scope_type

scope_id

points

reason

created_at

This supports:

- History

- Auditability

- Corrections

- Reversals

- Reports

- Achievements

- Badges

# 22. Point Permissions
Point awarding must be permission-controlled.

For example:

Teacher → students in assigned classes

Parent → own child

A parent should not be able to award points to someone else's child.

A teacher should not be able to award points outside authorized classes.

# 23. Badges and Achievements
Achievements are built from engagement events.

Examples:

100 Points

Reading Champion

Perfect Attendance

Mathematics Star

Helpful Student

Excellent Participation

Schools can enable or disable:

Badges

Leaderboards

Public rankings

Avoid assuming that public student rankings are appropriate for every school.

# 24. Assignments Module
Teachers can create:

- Homework

- Classwork

- Projects

- Revision

- Quizzes

- Other assignments

Example:

Mathematics Exercise 4

Grade 5A

Due: Friday

Attachment:

PDF

Students can submit:

- Text

- Photos

- PDFs

- Documents

# 25. Assessment Module
Support:

Quiz

Exam

Assignment

Project

Participation

Custom assessment

Assessment configuration should be school/education-system aware.

# 26. Grading
Do not hard-code a single grading model.

Support:

Percentage

Numeric

Letter

GPA

Custom

Example:

A = 90–100

B = 80–89

Another school can use a completely different system.

# 27. Student Progress

> **Revised (26 November 2026, D33). There is no course progress; progress is attendance, assignments, grades, assessment results, points, achievements, portfolio and teacher feedback.**
Students and authorized parents/guardians can see:

- Attendance

- Assignments

- Grades

- Assessment results

- Points

- Achievements

- Course progress

- Teacher feedback

All student data access must follow authorization rules.

# 28. Communication Module
Use one shared messaging infrastructure.

Conversation types:

DIRECT

GROUP

CLASS

SCHOOL

COURSE

TUTORING

Core entities:

conversations

conversation_members

messages

message_attachments

message_reads

message_reactions

# 29. Parent ↔ Teacher Messaging
A parent can communicate with teachers associated with their child.

Parent

↓

Child

↓

Class

↓

Teacher

The backend must validate the relationship.

A teacher can communicate with authorized parents of students in their assigned classes.

# 30. Group Chat
Possible groups:

Class group

Parent group

School group

Course group

Tutoring group

Examples:

Grade 5A Parents

Grade 5A

Grade 5 Teachers

Mathematics Course

Physics Study Group

Each group must have explicit membership.

# 31. Group Chat Permissions
Members should not be able to add arbitrary users unless their role and group permissions allow it.

For example:

Teacher

→ can manage class group

Parent

→ can participate in class parent group

Student

→ can participate in approved student groups

School administrators can configure who can create and moderate groups.

# 32. Messaging Translation
Messages should retain their original language.

Example:

Original:

English

Available translation:

Amharic

Users can select:

View translated

View original

Machine translations should be clearly identified where necessary.

# 33. Digital Library
Each school can maintain its own digital library.

School

└── Digital Library

│

├── Grade 5

│ ├── Mathematics

│ ├── English

│ └── Science

│

└── Grade 6

├── Mathematics

└── Science

Resources:

- Books

- Notes

- PDFs

- Documents

- Presentations

- Audio

- Video

- Revision material

- Exam papers

# 34. Library Access
Resources should have explicit access rules.

PUBLIC

SCHOOL_ONLY

GRADE_ONLY

CLASS_ONLY

COURSE_ONLY

ENROLLED_ONLY

PRIVATE

PAID

Example:

Grade 5 Mathematics Notes

→ accessible to Grade 5 students of School A

A Grade 6 student from another school cannot access the resource.

# 35. Content Ownership
Resources should identify ownership:

SCHOOL

TEACHER

TUTOR

ORGANIZATION

PLATFORM

LICENSED_CONTENT

USER

This is important for copyright, licensing, and takedown workflows.

The platform must not assume that a user has the right to distribute an uploaded book simply because they uploaded it.

# 36. Marketplace / Learning Ecosystem

> **Revised (26 November 2026, §135, D32, D33). The ecosystem is the school platform plus a tutor directory. Online courses, recorded content and hosted video are removed.**
The **Marketplace module** contains all learning/tutoring activity that is not simply an official school class.

This includes:

Marketplace

│

├── Tutor Profiles

├── Tutor Services

├── Online Courses

├── Pre-recorded Courses

├── Live Courses

├── Hybrid Courses

├── Course Enrollment

├── Tutor Discovery

├── Tutor Booking

├── Tutoring Sessions

├── Video Sessions

├── Reviews

├── Course Payments

└── Tutor Payments/Payouts

This is the correct place for the tutoring ecosystem.

# 37. Three Different Learning Concepts

> **Revised (26 November 2026, §135). Only two concepts remain: the **school class** and the **tutor listing**. The "online course" concept is removed (D33), and a tutor listing is a directory entry, not a service the platform delivers (D32).**
The platform must distinguish:

## A. School Class
Official school enrollment.

Grade 8A

## B. Online Course
Structured learning programme.

Grade 8 Mathematics Revision

## C. Tutoring Service
Bookable tutoring offering.

1-to-1 Mathematics — 60 minutes

Do not merge these concepts into one entity.

# 38. Online Course Delivery Modes

> **Removed (26 November 2026, §135, D33). No courses, live sessions, recordings or hosted video are built.**
An online course can be:

PRE_RECORDED

LIVE

HYBRID

## Pre-recorded
Students consume prepared content.

Video

Reading

Quiz

Assignment

## Live
Students attend scheduled teaching sessions.

Scheduled session

↓

Video meeting

↓

Tutor + students

## Hybrid
Combination of:

Pre-recorded lessons

\+

Live tutoring

\+

Assignments

\+

Quizzes

# 39. Online Course Ownership

> **Removed (26 November 2026, §135, D33). No courses, live sessions, recordings or hosted video are built.**
Courses can be created by:

SCHOOL

TEACHER

TUTOR

ORGANIZATION

PLATFORM

Examples:

School

→ Grade 10 Exam Preparation

Teacher

→ Mathematics Revision Course

Tutor

→ GCSE Physics Course

# 40. Teacher Marketplace Participation

> **Revised (26 November 2026, §135). A teacher may list as a tutor with a **separate** directory profile; there are no courses to publish.**
A teacher may have:

Teacher Profile

\+

Optional Tutor Profile

These should remain logically separate.

School employment information does not automatically become marketplace information.

A teacher may therefore:

Teach Grade 8 at School A

and separately:

Offer Mathematics tutoring on the marketplace

subject to platform and school policies.

# 41. Course Enrollment

> **Removed (26 November 2026, §135, D33). No courses, live sessions, recordings or hosted video are built.**
Enrollment is its own entity.

course_enrollments

------------------

id

course_id

student_id

status

enrollment_type

requested_at

approved_at

approved_by

enrolled_at

completed_at

Enrollment modes:

PUBLIC

INVITE_ONLY

APPROVAL_REQUIRED

SCHOOL_ONLY

CLASS_ONLY

PAID

FREE

# 42. Teacher-Controlled Course Enrollment

> **Removed (26 November 2026, §135, D33). No courses, live sessions, recordings or hosted video are built.**
A teacher can create:

> Grade 10 Mathematics Live Tutoring

and configure:

Enrollment:

APPROVAL_REQUIRED

Flow:

Student

↓

Request enrollment

↓

Teacher reviews

↓

Teacher approves

↓

Student becomes enrolled

↓

Student can access course

Only enrolled students can access course content.

# 43. Course Content

> **Removed (26 November 2026, §135, D33). No courses, live sessions, recordings or hosted video are built.**
A course may contain:

Course

│

├── Section

│ ├── Lesson

│ ├── Material

│ ├── Quiz

│ └── Assignment

│

└── Final Assessment

Lesson types:

VIDEO

TEXT

PDF

AUDIO

LIVE_SESSION

QUIZ

ASSIGNMENT

# 44. Live Course Sessions

> **Removed (26 November 2026, §135, D33). No courses, live sessions, recordings or hosted video are built.**
A live course contains scheduled sessions.

course_live_sessions

--------------------

id

course_id

title

scheduled_start

scheduled_end

timezone

meeting_provider

meeting_id

recording_media_id

status

Only enrolled students can receive authorization to join.

# 45. Live Course Flow

> **Removed (26 November 2026, §135, D33). No courses, live sessions, recordings or hosted video are built.**
Tutor

↓

Creates Course

↓

Creates Live Sessions

↓

Students enroll

↓

Approved students receive access

↓

At session time

↓

Backend creates/authorizes video session

↓

Tutor + enrolled students join

The video provider handles media transport.

The platform controls authorization.

# 46. Live Session Recording

> **Removed (26 November 2026, §135, D33). No courses, live sessions, recordings or hosted video are built.**
A live session can optionally become a recording.

Live Session

↓

Video Provider

↓

Recording

↓

Cloud Storage

↓

Course Lesson / Recording

The recording can be available only to enrolled students.

# 47. Hybrid Course

> **Removed (26 November 2026, §135, D33). No courses, live sessions, recordings or hosted video are built.**
Example:

Grade 9 Chemistry

│

├── Chapter 1 video

├── Chapter 2 video

├── Reading notes

├── Weekly live class

├── Weekly quiz

└── Final assessment

The platform should support both asynchronous and scheduled content in one course.

# 48. Tutor Marketplace

> **Revised (26 November 2026, §135, D32). This is now the **tutor directory**. The search filters stand except rating (there are no reviews). Contact is by platform message, never by booking.**
Independent students/parents can discover tutors.

Search filters:

Subject

Grade/Age

Language

Country

City

Online/In-person

Price

Availability

Rating

Verified status

# 49. Tutor Profile

> **Revised (26 November 2026, §135). The fields stand, with an area-level location instead of an address. No intro video, no reviews and no rating are kept.**
Tutor Profile

-------------

Name

Photo

Bio

Experience

Languages

Subjects

Age groups

Teaching modes

Location

Online availability

Verification status

Related information:

Qualifications

Subjects

Languages

Availability

Services

Reviews

# 50. Tutor Services

> **Removed (26 November 2026, §135, D32). There are no services, bookings, sessions, reviews or lesson payments on the platform.**
A tutor can create multiple services.

Example:

Tutor

│

├── Mathematics 1-to-1

├── Physics Exam Preparation

└── Grade 8 Weekly Tutoring

Each service can define:

Duration

Price

Currency

Delivery mode

Maximum students

Availability

Booking rules

Cancellation policy

# 51. Personal Tutoring

> **Removed (26 November 2026, §135, D32). There are no services, bookings, sessions, reviews or lesson payments on the platform.**
A student or parent can:

Search tutor

↓

View service

↓

Choose time

↓

Book

↓

Pay

↓

Attend session

For minors, the platform should support guardian approval/payment policies.

# 52. Tutoring Types

> **Removed (26 November 2026, §135, D32). There are no services, bookings, sessions, reviews or lesson payments on the platform.**
Support:

ONE_TO_ONE

SMALL_GROUP

and:

ONLINE

IN_PERSON

The same tutor can offer multiple services.

# 53. Tutor Availability

> **Revised (26 November 2026, §135). Availability is an **informational weekly pattern** shown on the listing; nothing is reserved and there are no slots or exceptions.**
Create structured availability instead of storing only free-form text.

tutor_availability

------------------

tutor_id

day_of_week

start_time

end_time

timezone

active

Then bookings reserve actual slots.

# 54. Tutoring Booking

> **Removed (26 November 2026, §135, D32). There are no services, bookings, sessions, reviews or lesson payments on the platform.**
booking

-------

id

student_id

parent_id

tutor_id

service_id

scheduled_start

scheduled_end

status

price

currency

payment_id

Possible states:

PENDING

CONFIRMED

CANCELLED

COMPLETED

NO_SHOW

REFUNDED

# 55. Tutoring Session

> **Removed (26 November 2026, §135, D32). There are no services, bookings, sessions, reviews or lesson payments on the platform.**
A confirmed booking creates a session.

tutoring_session

----------------

id

booking_id

session_type

delivery_mode

meeting_provider

meeting_id

started_at

ended_at

status

# 56. Video Infrastructure

> **Removed (26 November 2026, §135, D33). No courses, live sessions, recordings or hosted video are built.**
Do not implement the video transport system yourself initially.

Use a managed provider such as Agora.

Architecture:

Platform Backend

│

▼

Create/Authorize Session

│

▼

Managed Video Provider

│

┌───┴────┐

│ │

Tutor Student

Your application owns:

- Session identity

- Participant authorization

- Schedule

- Booking

- Access tokens

- Session status

- Recording metadata

The provider owns:

- Audio/video transport

- Connectivity

- TURN/media infrastructure

- Real-time media

# 57. Course vs Tutoring Video

> **Removed (26 November 2026, §135, D33). No courses, live sessions, recordings or hosted video are built.**
There are two different video contexts:

LIVE COURSE

│

└── Multiple enrolled students + teacher/tutor

and:

TUTORING SESSION

│

├── 1-to-1

└── Small group

They can share the video infrastructure but should remain separate domain concepts.

# 58. Tutor Reviews

> **Removed (26 November 2026, §135, D32). There are no services, bookings, sessions, reviews or lesson payments on the platform.**
After a completed tutoring session/service, authorized users can review.

tutor_reviews

-------------

id

tutor_id

booking_id

reviewer_user_id

rating

comment

created_at

status

Reviews should be associated with real transactions/bookings.

# 59. Marketplace Payments

> **Removed (26 November 2026, §135, D32). There are no services, bookings, sessions, reviews or lesson payments on the platform.**
Marketplace payments cover:

Paid courses

Tutor bookings

Subscriptions

Potential flow:

Customer

↓

Order

↓

Payment

↓

Platform

├── Platform fee

└── Tutor/provider earnings

Tutor payouts should be implemented separately from payment collection.

# 60. School Payments
School payments are a different business domain.

Examples:

Tuition

Transport

Meals

Registration

Other school charges

Do not mix school fee invoices directly with tutor booking objects.

They can share the payment infrastructure but should have separate domain models.

# 61. Payment Provider Abstraction
Use:

PaymentService

│

├── Provider A

├── Provider B

├── Provider C

└── ...

Country configuration determines which providers are available.

This allows:

Ethiopia → local payment providers

Kenya → M-Pesa/etc.

Other countries → different providers

The main payment domain should not be hard-coded around one country.

# 62. Calendar & Events
The calendar aggregates events from the platform.

Calendar

│

├── School Events

├── Class Events

├── Exams

├── Assignment Deadlines

├── Course Sessions

├── Tutoring Sessions

├── Parent Meetings

└── Country Holidays

A user sees events only where they have access.

# 63. Calendar Event Model
event

-----

id

scope_type

scope_id

event_type

title

description

start_at

end_at

timezone

all_day

created_by

status

Event types:

SCHOOL_EVENT

CLASS_EVENT

EXAM

ASSIGNMENT_DEADLINE

COURSE_SESSION

TUTOR_SESSION

PARENT_MEETING

HOLIDAY

# 64. Country Calendars
Store timestamps canonically using:

TIMESTAMPTZ

Display according to user/country settings.

Support:

GREGORIAN

ETHIOPIAN

and allow future calendar systems.

Do not store Ethiopian calendar dates as the canonical database representation.

# 65. Country Configuration
Country-specific configuration can include:

Country

├── Currency

├── Languages

├── Timezone

├── Calendar

├── Holidays

├── Phone country code

├── Address rules

├── Education structures

├── Grading systems

└── Payment providers

# 66. Initial Ethiopia Configuration
Example:

Country:

Ethiopia

Code:

ET

Currency:

ETB

Languages:

Amharic

English

Afaan Oromo

Tigrinya

Somali

Calendars:

Gregorian

Ethiopian

Timezone:

Africa/Addis_Ababa

# 67. Future Country Example
Kenya:

Country:

Kenya

Code:

KE

Currency:

KES

Languages:

English

Swahili

Calendar:

Gregorian

Timezone:

Africa/Nairobi

The core application remains unchanged.

# 68. Address Model
Avoid an Ethiopia-specific database such as:

region

zone

woreda

kebele

as universal columns.

Use generic administrative levels:

address

-------

country

administrative_level_1

administrative_level_2

administrative_level_3

locality

postal_code

address_line_1

address_line_2

The UI labels can be country-specific.

# 69. Localization
Localization has several layers:

UI

Country

School

User

Content

User settings:

language

locale

timezone

calendar_preference

# 70. Initial Languages
Ethiopia:

Amharic

English

Afaan Oromo

Tigrinya

Somali

But languages must be data/configuration, not a hard-coded enum requiring code changes whenever a new language is added.

# 71. Translation
Distinguish:

### UI translations
Application strings.

### User-generated content
Messages, posts, announcements.

### Educational content
Courses and materials.

These should not automatically use exactly the same translation mechanism.

# 72. Low-Connectivity Design
Offline functionality should be part of the architecture.

Especially:

Attendance

Class roster

Recent assignments

Recent messages

Calendar metadata

Course metadata

Example:

Internet unavailable

↓

Teacher marks attendance

↓

Local database

↓

Internet returns

↓

Synchronization

↓

Server

The synchronization mechanism needs conflict handling and idempotency.

# 73. Mobile Applications
Use one mobile application for all countries.

Recommended:

React Native

Expo

TypeScript

Role-based navigation can expose the appropriate features.

# 74. Parent Mobile Experience
Home

Children

Messages

Learning

Tutors

Calendar

Payments

Notifications

Profile

Parent home should prioritize child activity:

Child

├── Attendance

├── Points

├── Assignments

├── Grades

├── Class posts

├── Course progress

└── Upcoming events

# 75. Student Mobile Experience
Home

Classes

Courses

Library

Tutors

Messages

Calendar

Points

Profile

The student sees only:

Their enrolled school classes

\+

Their enrolled courses

\+

Their authorized resources

# 76. Teacher Mobile Experience
Home

Classes

Attendance

Points

Assignments

Students

Posts

Stories

Messages

Courses

Calendar

Profile

A teacher sees only classes they are assigned to.

# 77. Tutor Mobile Experience
Home

Services

Courses

Bookings

Students

Messages

Calendar

Earnings

Profile

Marketplace access is separate from school access.

# 78. School Administration Web
School administrators should have a richer web dashboard:

Dashboard

Students

Teachers

Guardians

Classes

Academic Year

Attendance

Assignments

Grades

Library

Feed

Messages

Calendar

Payments

Reports

Feature Configuration

School Settings

# 79. Platform Administration
Platform administrators manage:

Countries

Organizations

Schools

Users

Subscriptions

Payments

Tutors

Tutor verification

Moderation

Feature flags

System health

Support

Audit logs

# 80. Feature Flags
Features should be configurable by:

Platform

Country

Organization

School

Example:

School A

✓ Attendance

✓ Points

✓ Feed

✓ Messaging

✓ Library

✓ Assignments

✗ Marketplace

✗ Tutoring

School B

✓ Attendance

✓ Points

✓ Feed

✓ Messaging

✓ Library

✓ Courses

✓ Marketplace

✓ Tutoring

✓ Payments

This makes the platform flexible without creating different applications.

# 81. Social Feed Scope
Feed scopes should be explicit.

CLASS

SCHOOL

COURSE

GROUP

Avoid a generic feed where authorization is ambiguous.

Every post should have a clear scope.

# 82. Class Content Authorization
Before returning any class content, the backend determines:

Current user

\+

Requested class

\+

Relationship to class

For example:

Student

→ class_students

→ allowed

Teacher

→ class_teachers

→ allowed

Parent

→ guardian

→ child

→ class_students

→ allowed

Otherwise:

DENIED

This applies to:

- Posts

- Stories

- Comments

- Class documents

- Class media

- Assignments

- Attendance

- Class chats

- Class events

- Class points

# 83. Course Authorization

> **Removed (26 November 2026, D33). There are no courses.**
Course access is independent from school class membership.

A student sees course content only if:

course_enrollment.status = ACTIVE

or another explicit access rule grants access.

A class member does not automatically become enrolled in every unrelated course.

# 84. Library Authorization
Similarly:

Student

→ enrolled class/grade/school

→ resource access rules

→ allowed/denied

Library access must be checked server-side.

# 85. Tutor Authorization

> **Stands (26 November 2026, §135). In the directory a tutor receives **no** school data at all and there are no sharing grants.**
A tutor should receive only the information necessary for a tutoring relationship.

For example:

Tutor

→ tutoring booking

→ student identity/basic tutoring information

does not automatically mean:

Tutor → student's school grades

Tutor → attendance

Tutor → parent messages

Tutor → school profile

unless an explicit permission model allows it.

# 86. Child Safety
The platform must treat children as a special data category operationally.

Important controls:

- Strong authorization

- Restricted discovery

- Guardian relationships

- Reporting

- Moderation

- Tutor verification

- Appropriate communication restrictions

- Audit logs

- Safe media publishing

- Controlled tutoring interactions

For minors, parent/guardian participation should be configurable and supported throughout the marketplace.

# 87. Moderation
Moderatable objects include:

Posts

Stories

Comments

Photos

Videos

Messages

Tutor profiles

Courses

Learning materials

Users

Reviews

Workflow:

User content

↓

Automatic checks where appropriate

↓

Flagged / allowed

↓

Moderator review

↓

Decision

↓

Audit record

# 88. Tutor Verification
Tutor status:

UNVERIFIED

PENDING_REVIEW

VERIFIED

SUSPENDED

REJECTED

Possible verification information:

Identity

Qualifications

Experience

Certificates

Country-specific requirements

Verification requirements can differ by country.

# 89. Media Infrastructure
Use object storage for:

- Photos

- Videos

- Books

- PDFs

- Course videos

- Student work

Recommended:

Spring Boot

↓

Signed upload

↓

Cloudflare R2 / S3

↓

CDN

Do not store large media files directly in PostgreSQL.

# 90. Media Organization
Example object keys:

schools/{schoolId}/posts/{postId}/...

schools/{schoolId}/stories/{storyId}/...

schools/{schoolId}/library/{resourceId}/...

courses/{courseId}/lessons/{lessonId}/...

users/{userId}/uploads/...

# 91. Background Jobs
Asynchronous processing should handle:

Notifications

SMS

Email

Translation

Image resizing

Video processing

Video recording processing

Document processing

Moderation

Report generation

Payment reconciliation

Use a queue and worker architecture.

A transactional outbox is recommended for reliable domain events.

# 92. Real-Time Infrastructure
Use WebSockets where real-time behavior is actually needed.

Examples:

Chat messages

Typing status

Read receipts

Presence

Real-time notifications

Live course events

Normal CRUD operations should continue through ordinary HTTP APIs.

# 93. Notification System
Centralize notifications.

Events include:

ATTENDANCE_MARKED

NEW_MESSAGE

NEW_ASSIGNMENT

NEW_GRADE

POINT_AWARDED

NEW_POST

NEW_STORY

POST_APPROVED

COURSE_INVITATION

COURSE_APPROVED

TUTOR_BOOKING

PAYMENT_COMPLETED

UPCOMING_EVENT

TUTOR_SESSION_REMINDER

Channels:

PUSH

IN_APP

EMAIL

SMS

Users should have notification preferences.

# 94. Database — High-Level Structure

> **Superseded (26 November 2026). The schema is `Database_Design.md`; the course, live-session, booking, order, payout and AI groups below are not built (D32–D34).**
GLOBAL

├── countries

├── languages

├── currencies

├── calendars

└── system_settings

TENANCY

├── organizations

├── schools

├── campuses

└── tenant_settings

IDENTITY

├── users

├── roles

├── permissions

├── user_roles

└── sessions

PEOPLE

├── students

├── guardian_profiles

├── teacher_profiles

├── tutor_profiles

└── student_guardians

ACADEMIC

├── academic_years

├── academic_periods

├── grade_levels

├── classes

├── subjects

├── class_students

└── class_teachers

CLASSROOM

├── attendance

├── attendance_records

├── assignments

├── submissions

├── assessments

├── assessment_results

└── progress

ENGAGEMENT

├── point_categories

├── point_transactions

├── badges

└── achievements

SOCIAL

├── posts

├── stories

├── comments

├── reactions

├── post_media

└── reports

MESSAGING

├── conversations

├── conversation_members

├── messages

├── message_media

├── message_reads

└── message_reactions

LIBRARY

├── resources

├── resource_categories

├── resource_access_rules

└── resource_collections

COURSES

├── courses

├── course_sections

├── course_lessons

├── course_materials

├── course_enrollments

├── lesson_progress

├── quizzes

├── quiz_questions

└── quiz_attempts

COURSE LIVE

├── course_live_sessions

└── course_live_participants

TUTOR MARKETPLACE

├── tutor_services

├── tutor_subjects

├── tutor_languages

├── tutor_availability

├── tutor_bookings

├── tutoring_sessions

├── tutoring_participants

└── tutor_reviews

CALENDAR

├── events

├── event_participants

└── holidays

FINANCE

├── orders

├── invoices

├── payments

├── payment_transactions

├── refunds

├── tutor_earnings

├── tutor_payouts

└── platform_commissions

MEDIA

├── media

└── media_access

NOTIFICATIONS

├── notifications

├── push_devices

└── notification_preferences

PLATFORM

├── subscriptions

├── feature_flags

├── audit_logs

└── moderation_cases

# 95. School vs Marketplace Data

> **Revised (26 November 2026, §135). The marketplace world is only the tutor directory (profiles, listings, inquiries, listing subscriptions); it never holds school data.**
These two worlds should remain conceptually separate.

SCHOOL WORLD

│

├── School

├── Class

├── Student

├── Teacher

├── Guardian

├── Attendance

├── Grades

├── Points

├── Class Feed

└── School Library

MARKETPLACE WORLD

│

├── Tutor

├── Tutor Service

├── Course

├── Enrollment

├── Booking

├── Tutoring Session

├── Video

├── Reviews

├── Payments

└── Payouts

They can share:

Users

Media

Messaging

Calendar

Notifications

Payments

Video infrastructure

but the business rules remain separate.

# 96. Recommended Spring Boot Structure

> **Revised (26 November 2026). There are no `courses`, `video`, `marketplace` (bookings), `finance` (payouts) or `ai` modules; the tutor directory is a `tutors` module and school fees a `fees` module (Implementation Plan).**
com.platform

│

├── identity

├── authorization

├── tenancy

├── country

├── localization

│

├── organization

├── school

├── campus

├── academic

│

├── people

├── student

├── teacher

├── guardian

├── tutor

│

├── classroom

├── attendance

├── points

├── achievements

├── assignments

├── assessment

├── grading

├── progress

│

├── social

├── posts

├── stories

├── messaging

├── moderation

│

├── library

│

├── marketplace

│ ├── courses

│ ├── tutors

│ ├── services

│ ├── bookings

│ ├── sessions

│ └── reviews

│

├── calendar

├── notifications

├── media

│

├── payments

├── finance

├── subscriptions

├── payouts

│

├── reporting

├── analytics

└── platform_admin

# 97. Recommended Technology Stack
## Mobile
React Native

Expo

TypeScript

## Web
Next.js

TypeScript

Tailwind CSS

shadcn/ui

## Backend
Java 21

Spring Boot

Spring Security

Spring Data JPA

## Database
PostgreSQL

## Cache / queues
Redis

## Storage
Cloudflare R2

## Notifications
Firebase Cloud Messaging

APNs

## Real-time
WebSocket

## Video
Managed provider such as Agora

## Infrastructure
Docker

Cloudflare

Managed PostgreSQL

Linux/cloud infrastructure

# 98. Multi-Tenant Isolation Strategy
Initially:

Shared PostgreSQL database

\+

Strong tenant isolation

Tenant-owned records should carry appropriate tenant information.

Examples:

students.school_id

classes.school_id

assignments.school_id

posts.school_id

resources.school_id

For marketplace entities that are not school-owned:

tutor_profiles

courses

tutor_services

bookings

their scope is determined by their own ownership/context.

# 99. Authorization Architecture
Every sensitive request should conceptually evaluate:

Who?

\+

What action?

\+

Which resource?

\+

Which context?

\+

What relationship?

Example:

Can User A VIEW Post B?

The authorization engine determines:

Post B

→ Class 5A

User A

→ Student

→ member of Class 5A

RESULT:

ALLOW

Another:

User A

→ Student

→ member of Class 6A

Post B

→ Class 5A

RESULT:

DENY

# 100. Class Content Security Rule
No client-side filtering should be trusted.

Bad:

API returns all school posts

↓

Mobile app hides posts from other classes

Correct:

API request

↓

Backend authorization

↓

Only authorized content returned

This must apply to every endpoint.

# 101. Course Security Rule

> **Removed (26 November 2026, D33). There are no courses.**
Course content should similarly be filtered server-side.

A student can access:

Course A

only if they have:

ACTIVE enrollment

or another explicit permission.

A course ID alone must never be sufficient to retrieve course materials.

# 102. Parent Access Rule
Parent access to school data should be based on child relationships.

Parent

↓

Guardian relationship

↓

Child

↓

School/class/course relationships

↓

Authorized information

This should apply to:

Attendance

Grades

Points

Assignments

Class feed

Stories

Class messages

School events

# 103. School Data and Marketplace Data

> **Stands, with the directory of §135: a school never sees a family's tutor inquiries, and a tutor never sees school data.**
A parent's access to a child's school record should not automatically expose:

Tutor marketplace history

Likewise, a tutor should not automatically see:

School grades

School attendance

Class messages

unless an explicit integration/permission grants that access.

This separation is important for privacy and business boundaries.

# 104. Offline Synchronization
For offline features, use:

Local database

\+

Sync queue

\+

Server reconciliation

Example:

Teacher

↓

Mark attendance offline

↓

Local record created

↓

Sync operation queued

↓

Network returns

↓

Server receives operation

↓

Idempotency check

↓

Apply

↓

Sync confirmation

Avoid blindly replacing whole datasets.

# 105. Subscription Model
Schools can subscribe to platform packages.

Potential plans:

FREE

BASIC

STANDARD

PREMIUM

ENTERPRISE

Feature limits may be based on:

Students

Teachers

Storage

Messaging

Courses

Library

Payments

Reports

Video

AI

Tutors may have a separate marketplace business model.

# 106. Marketplace Revenue

> **Revised (26 November 2026, §135, D35). Platform revenue is **school subscriptions and tutor listing subscriptions** only. There are no course sales, booking commissions or payouts.**
Potential revenue:

School subscriptions

\+

Paid courses

\+

Tutor booking commissions

\+

Tutor subscription plans

\+

Premium learning products

Keep platform revenue and school/tutor earnings as separate accounting concepts.

# 107. Country Expansion
Adding a new country should approximately be:

1\. Create country

2\. Configure currency

3\. Configure languages

4\. Configure calendar

5\. Configure education structure

6\. Configure grading

7\. Configure holidays

8\. Configure payment providers

9\. Add translations

10\. Enable country

It should not require creating another codebase.

# 108. Example: Ethiopia
Ethiopia

│

├── ETB

├── Amharic

├── English

├── Afaan Oromo

├── Tigrinya

├── Somali

├── Ethiopian Calendar

├── Gregorian Calendar

└── Ethiopian payment providers

# 109. Example: Kenya
Kenya

│

├── KES

├── English

├── Swahili

├── Gregorian Calendar

└── Kenyan payment providers

Same product.

Different configuration.

# 110. School-Level Localization
Country defaults should not prevent school customization.

For example:

Country:

Ethiopia

School:

Uses English + Amharic

Another:

Country:

Ethiopia

School:

Uses English only

Another:

Country:

Kenya

School:

Uses English + Swahili

# 111. User-Level Localization
Users should independently select:

Language

Locale

Timezone

Calendar display preference

Example:

Teacher → English

Parent → Amharic

Student → Afaan Oromo

The same platform can serve all three.

# 112. Recommended MVP — Phase 1
The first release should deliver a reliable ClassDojo-style school and family experience. Keep the scope focused on daily school communication and classroom engagement.

- Identity, authentication, multi-tenancy and school/organization setup

- Teacher, student, parent/guardian and school administrator accounts

- Guardian verification, child linking and role/membership-based authorization

- Academic years, grades, classes and class membership

- Attendance with offline capture and safe synchronisation

- Positive behaviour points, configurable categories, badges and an auditable points ledger

- Class feed, stories, teacher posts and school announcements

- Parent-teacher messaging with controlled communication permissions

- Push and in-app notifications, calendar and basic media uploads

- Basic student portfolio submission: photos, files, drawings and teacher approval

- CSV/Excel import for students, classes, teachers and guardian invitations

- A small teacher toolkit: quick attendance, classroom timer and random student picker

- Multilingual interface foundation and low-data mobile experience

This phase should prove that schools, teachers and families use the platform regularly before expanding into a full LMS or tutoring marketplace.

# 113. Phase 2 — School Learning
Add structured teaching, assessment and evidence-of-learning capabilities.

- Assignments, homework, projects, submissions and teacher feedback

- Assessments, quizzes, examinations, grading schemes and result publication

- Student progress views for teachers, students and authorised guardians

- Digital library with grade, subject, class and enrolment-based access rules

- Curriculum mapping from country/education system to grade, subject, topic and learning objective

- Student portfolios with collections, reflections, teacher feedback and parent visibility controls

- Teacher lesson preparation and reusable classroom activity templates

Keep curriculum content configurable and versioned; do not hard-code one national syllabus.

# 114. Phase 3 — Online Learning Marketplace

> **Removed (26 November 2026, D33). Phase 3 is now **payments and school fees** (Implementation Plan).**
Introduce structured online courses and learning content, separate from official school classes.

- Teacher/tutor course creator profiles and course ownership

- Pre-recorded, live and hybrid courses

- Sections, lessons, learning materials, quizzes and course assignments

- Course enrolment, approval, access control and progress tracking

- Course discussions and learner notifications

- Course pricing and payments where the local market is ready

# 115. Phase 4 — Tutor Marketplace

> **Revised (26 November 2026, D32). Phase 4 is the **tutor directory** (§135).**
Launch parent-led discovery and booking of independent tutors. A school teacher profile and a public marketplace tutor profile must remain separate.

- Tutor profiles, identity checks, qualification evidence and country-specific verification

- Tutor services, subjects, grade/age groups, languages, delivery modes and pricing

- Availability, scheduling, booking, cancellation and rescheduling workflows

- Transparent tutor matching using subject, learning goal, level, language, availability, price and teaching preferences

- Parent/guardian-led booking and payment for minors

- Trial or introductory lessons and post-trial recommendations

- One-to-one and small-group tutoring, online and in-person where permitted

- Managed video sessions, session access controls and optional recording subject to consent

- Reviews linked to completed bookings and safety/reporting workflows

# 116. Phase 5 — Marketplace Finance & Tutor Insights

> **Revised (26 November 2026, D32, D37). Commissions, earnings, payouts and tutor insights are removed. Phase 5 is platform billing and reconciliation; school fees moved to Phase 3.**

Complete the commercial operations needed to operate tutoring and paid learning reliably.

- Orders, payments, payment reconciliation, refunds and cancellation rules

- Platform commissions, tutor earnings, payout eligibility and payout records

- Separate school fee billing from tutor booking and course payment models

- Tutor dashboard: upcoming lessons, completed sessions, earnings, cancellations and attendance

- Tutor quality insights: verified reviews, punctuality, lesson completion and learner progress signals

- Parent-facing tutoring history, session notes and progress summaries

Avoid opaque tutor rankings; explain recommendation factors and allow parents to choose.

# 117. Phase 6 — AI Tutoring & Advanced Platform

> **Removed (26 November 2026, D34). There is no Student AI Tutor or Tutor Copilot, and no AI phase. The school AI assistant (§134.9) is deferred.**

Introduce AI in two separate products with shared curriculum and learning infrastructure: AI assistance for human tutors/teachers, and an optional student-facing AI learning tutor.

- AI Tutor Copilot: lesson planning, learning-goal preparation, activity suggestions and question generation

- AI Tutor Copilot: optional in-session teaching prompts and learner misconception signals

- AI Tutor Copilot: post-session summary, homework suggestions, progress notes and parent report drafts

- Student AI Tutor: age-appropriate conversational explanations, hints, worked examples and guided practice

- Student AI Tutor: diagnostic checks, adaptive practice and progress against approved learning objectives

- AI translation, multilingual learning support, teacher tools and automated administrative summaries

- AI outputs must be reviewable by a responsible teacher/tutor/guardian where appropriate and must not be treated as authoritative grading or diagnosis.

Expand country by country only after curriculum, language quality, safeguarding, payments and support processes are validated locally.

# 118. What Must Be Built Correctly From Day One
Even if the MVP is small, the following foundations should not be postponed:

✓ Multi-tenancy

✓ Role/membership model

✓ Child/guardian relationships

✓ Class-level authorization

✓ Course enrollment authorization

✓ Country configuration

✓ Localization architecture

✓ Media abstraction

✓ Payment abstraction

✓ Notification architecture

✓ Audit logging

These are expensive to retrofit later.

# 119. What Should NOT Be Overbuilt Initially
Do not start with:

Microservices everywhere

Complex AI infrastructure

Multiple search engines

Multiple payment providers

Full tutor payout system

Advanced recommendation engine

Huge analytics warehouse

Complex school ERP features (Section 134.7 adds a fee ledger and cash book only; payroll, tax and general accounting stay out of scope)

Start with strong boundaries inside a modular monolith.

# 120. Final Product Architecture

> **Revised (26 November 2026, §135): school platform plus tutor directory; no learning marketplace or AI tutoring.**
GLOBAL PLATFORM

│

COUNTRY / MARKET CONFIG

│

┌────────────────────┴────────────────────┐

│ │

SCHOOL ECOSYSTEM MARKETPLACE

│ │

┌───────┼────────┐ ┌────────┼────────┐

│ │ │ │ │ │

Classes People Community Courses Tutors Services

│ │ │ │ │ │

│ │ │ Pre-recorded │ 1-to-1

│ │ │ Live │ Small Group

│ │ │ Hybrid │ Online

│ │ │ │ │ In-person

│ │ │ │ │

Attendance │ Feed Enrollment │

Assignments │ Stories Progress │

Grades │ Chat Quizzes │

Points │ Media │

Library │ │

│ │ │

└───────┼─────────────────────────────────────────┘

│

STUDENTS

│

PARENTS

│

TEACHERS/TUTORS

│

Shared Platform Services

│

┌───────────┼───────────┬────────────┬─────────────┐

│ │ │ │ │

Identity Messaging Media Calendar Notifications

│ │ │ │ │

└───────────┴───────────┴────────────┴─────────────┘

│

Payments

│

Video

# 121. Core Access Model

> **Revised (26 November 2026, D33): course enrollment and course content access are removed.**
The most important rule for the platform is:

USER

│

├── SCHOOL MEMBERSHIP

│ │

│ └── CLASS MEMBERSHIP

│ │

│ └── CLASS CONTENT

│

├── GUARDIAN RELATIONSHIP

│ │

│ └── CHILD

│ │

│ └── CHILD'S CLASS CONTENT

│

└── MARKETPLACE RELATIONSHIP

│

├── COURSE ENROLLMENT

│ └── COURSE CONTENT

│

└── TUTOR BOOKING

└── TUTORING SESSION

This is the fundamental separation that keeps the system secure and understandable.

# 122. Final Product Definition

> **Revised (26 November 2026, §135): the product is a school platform (classroom, families, school operations and fees) plus a paid tutor directory.**
The platform can ultimately be described as:

> **A multi-country, multi-tenant African education ecosystem combining school management, ClassDojo-style student engagement, parent–teacher communication, classroom communities, digital school libraries, online courses, live and pre-recorded tutoring, personal tutor discovery, video learning, attendance, payments, and calendar functionality in one mobile-first platform.**

The product has three connected but distinct worlds:

EDUCATION ECOSYSTEM

│

┌────────────────┼────────────────┐

│ │ │

SCHOOL LEARNING MARKETPLACE

WORLD WORLD WORLD

│ │ │

Classes Courses Tutors

Teachers Lessons Services

Students Materials Bookings

Parents Quizzes Video

Attendance Progress Payments

Points

Feed

Chat

The **school world controls official school relationships**, the **learning world provides structured educational content**, and the **marketplace provides optional commercial tutoring and courses**.

All three can share the same global identity, messaging, media, calendar, notification, payment and video infrastructure, while maintaining separate authorization and business rules.

# 123. Product Specification Update — Scope and Authority

This update adds the capabilities described in Sections 124–135 (Section 135, added later on 26 November 2026, **revises the product scope**: the tutor marketplace is a paid directory, online courses and hosted video are removed, the Student AI Tutor and Tutor Copilot are removed, and the school AI assistant is deferred. Section 134, added 26 November 2026, covers school operations: timetable, lesson planning, assessment and report cards, attendance methods, student numbers and transfers, school fees and financial management, and the school AI assistant). It does not replace the original architecture. The original principles for global identity, multi-tenancy, explicit memberships, server-side authorization, separation of school and marketplace data, modular-monolith delivery and country configuration remain in force.

The roadmap in Sections 112–117 has been revised directly in this edition. Sections 124–133 add the detailed requirements that support those phases. If an earlier statement elsewhere in the document conflicts with these updated sections, use the updated phase and addendum requirements. The additions are intended to make the product more complete while keeping the first release focused.

# 124. Student Portfolios & Evidence of Learning

A student portfolio is a curated collection of a learner’s work over time. It is distinct from an assignment submission: an assignment is requested work for a defined task, while a portfolio item is evidence selected or shared to demonstrate learning, creativity, reflection or progress.

## Portfolio capabilities

- Students can create portfolio items using text, photos, scanned handwritten work, drawings, audio, video, PDFs and supported documents.

- Students can organise items into collections, subjects, projects or learning goals.

- Students can add a title, description, date, subject, grade/class context and a short reflection about what they learned.

- Teachers can comment, give feedback, request revisions, mark items as reviewed and optionally feature selected work.

- Schools configure whether portfolio items are private to the student and teacher, visible to linked guardians, or publishable to a class/school audience after approval.

- Guardians can view approved portfolio items and provide permitted encouragement or comments without changing teacher assessment records.

- Portfolio history must preserve authorship, review status and timestamps; deletion and retention follow school policy and applicable law.

## Suggested domain entities

portfolios, portfolio_items, portfolio_attachments, portfolio_collections, portfolio_comments, portfolio_feedback and portfolio_approvals. Reuse the shared media service and existing class/student authorization rules.

# 125. School Communications Centre & SIS/Roster Integration

## School Communications Centre

- Support school-wide, grade-wide, class-wide and targeted announcements, with an explicit recipient scope for every announcement.

- Allow authorised administrators to draft, schedule, publish, edit and withdraw announcements.

- Support in-app and push notifications at launch; add email and SMS/voice delivery through country-configured providers as operationally and commercially feasible.

- Track delivery, failure and read/acknowledgement status separately. Do not treat a push delivery receipt as proof that a person read the content.

- Allow translation of announcements while preserving the original text and clearly identifying machine-generated translations.

- Support urgent notices, event reminders, school closures and parent meeting invitations with appropriate audit history.

- Apply quiet hours and user notification preferences to non-urgent notifications; urgent safety notices may follow a separately configured policy.

## Student Information System and roster import

- Provide a guided CSV/Excel import for students, staff, classes, academic years, subjects and guardian contact invitations.

- Include column mapping, preview, validation errors, duplicate detection, dry-run mode and a downloadable error report before committing an import.

- Use stable external identifiers where available so subsequent imports can update records without creating duplicates.

- Record who initiated an import, source filename, time, row counts, rejected rows and changes applied.

- Support controlled parent/guardian invitation and relationship verification after import; an imported phone number or email alone must not automatically prove guardianship.

- Design a future integration adapter/API for school information systems, but do not make external SIS integrations a launch dependency.

Suggested entities include import_jobs, import_job_rows, external_systems, external_entity_mappings and sync_runs. Start with CSV/Excel; add vendor-specific connectors only when pilot schools require them.

# 126. Teacher Classroom Toolkit

Provide lightweight tools that reduce classroom administration and are fast to use on a phone or web dashboard.

- Quick attendance and late/absent status entry.

- Classroom timer and stopwatch.

- Random student picker and random group generator.

- Reusable activity instructions, classroom routines and simple checklists.

- Quick award of points with configurable positive behaviour categories and reasons.

- Quick capture of a student’s work into a portfolio, subject to the school’s media and consent policies.

Keep these tools lightweight and integrated into the teacher’s class view. Do not create a separate classroom app or build complex analytics into the first release.

# 127. Curriculum & Learning Standards

> **Revised (26 November 2026, D33, D34): the mentions of online courses, AI lesson planning and the student AI tutor no longer apply; curriculum tagging serves assignments, assessments, questions, lesson plans and portfolio items.**

Curriculum alignment is a shared foundation for school learning, online courses, tutor recommendations, AI lesson planning and the student AI tutor. It must be configurable by country and education system, and must support school-specific variations.

## Curriculum structure

Country / education system → curriculum version → academic level or grade → subject → topic or unit → learning objective or competency → optional standard/reference.

- Support curriculum versions with effective dates so schools can retain historical alignment when a syllabus changes.

- Allow a school to map its own subjects, terms and grade names to the national or regional curriculum where appropriate.

- Allow teachers to tag assignments, assessments, course lessons and portfolio items against one or more learning objectives.

- Track learning evidence and mastery as separate concepts: a quiz score or AI estimate is evidence, not automatically a definitive mastery decision.

- Support multiple grading systems and assessment rubrics without embedding one national grading policy in application code.

- Store the source, owner, licence and permission for curriculum documents and educational content.

Suggested entities include education_systems, curriculum_versions, academic_levels, curriculum_subjects, curriculum_topics, learning_objectives, curriculum_mappings and assessment_rubrics.

# 128. Tutor Marketplace Improvements

> **Revised (26 November 2026, §135). Trial lessons, post-trial assessments, matching and the tutor insights dashboard are removed; only the transparent ordering rule (no opaque ranking) and the parent-choice principle stand.**

## Intelligent tutor matching

- Collect the parent/student learning goal, subject, grade/age range, preferred language, current learning level, schedule, delivery mode and budget.

- Recommend tutors using transparent factors: subject fit, age/grade experience, language, availability, delivery mode, price, verified qualifications and relevant reviews.

- Do not expose sensitive school records to tutors during discovery. Share additional learner information only after an authorised booking and with appropriate guardian permission.

- Show why a tutor was recommended and let the parent compare and choose. Recommendations must not become an automatic assignment of a child to a tutor.

## Trial or introductory lessons

- Allow a tutor to offer a free or paid introductory session with clear duration, terms and availability.

- Require guardian-led booking and payment/approval for minors according to country and school policy.

- After the trial, the tutor can submit a structured assessment of goals, strengths, areas to practise and a proposed learning plan.

- AI may assist with drafting the assessment, but the tutor reviews and approves it before it is shared with the family.

- Allow the parent to book follow-up lessons, choose another tutor or close the trial without pressure.

## Tutor Insights dashboard

- Show upcoming and completed sessions, availability, attendance, cancellations, rescheduling, earnings and payout status.

- Show verified review summaries, lesson completion and learner progress signals for the tutor’s authorised tutoring relationships.

- Keep operational reliability metrics distinct from teaching effectiveness; do not infer teaching quality from punctuality or ratings alone.

- Give tutors actionable, non-punitive insights such as lesson preparation reminders, repeated scheduling conflicts and learning goals needing review.

# 129. AI Tutor Copilot — Support for Human Tutors and Teachers

> **Removed (26 November 2026, §135, D34). The Tutor Copilot is not built.**

AI Tutor Copilot is an assistant for the adult educator. It does not replace the tutor or teacher and must not independently make high-stakes decisions about a child.

## Before a lesson

- Generate a draft lesson plan from the subject, learner age/grade, curriculum objectives, prior tutor notes and selected learning goals.

- Suggest explanations, examples, activities, practice questions and suitable materials at the learner’s level.

- Highlight known areas for review from authorised prior assessments and session notes.

## During a lesson

- Optionally provide tutor-facing prompts, suggested follow-up questions and activity ideas.

- Where live transcription or session analysis is enabled, require the appropriate guardian/participant consent before capture begins and show a clear recording/transcription indicator.

- Allow the tutor to ignore, edit or dismiss suggestions; never interrupt the lesson with unnecessary AI notifications.

- Do not enable covert recording, always-on child monitoring or automatic emotion/personality inference.

## After a lesson

- Generate a draft session summary covering topics, activities, learner participation as observed, questions raised and agreed next steps.

- Suggest homework and follow-up practice aligned to the curriculum and session objectives.

- Draft a progress update for the parent/guardian and notes for the next tutor session.

- Identify possible learning gaps as hypotheses for the tutor to review, not as diagnoses or definitive conclusions.

- Require tutor review and approval before AI-generated notes, assessments or parent reports become part of the official learner record or are shared.

## AI Copilot controls

- Store the model/provider, prompt version, source context references, generation time, reviewer and approval status for important generated records.

- Respect role-based access: a tutor sees only data explicitly authorised for the tutoring relationship.

- Make AI usage, quotas and cost controls configurable by task type; keep AI model routing and billing/credit calculation as separate services.

# 130. Student AI Learning Tutor

> **Removed (26 November 2026, §135, D34). The Student AI Tutor is not built.**

Student AI Tutor is a separate student-facing learning experience. It helps a learner understand concepts and practise skills through guided interaction. It is distinct from AI Tutor Copilot, which supports a human educator.

## Core learning flow

1\. Identify the learner’s age/grade, subject, preferred language and applicable curriculum context using only authorised profile data.

2\. Ask what the learner wants to understand or practise, or let the learner select a topic and learning objective.

3\. Explain the concept in age-appropriate language, using examples and visual or step-by-step explanations where supported.

4\. Ask the learner to try a question. Prefer hints and guided reasoning before revealing a complete answer.

5\. Check the learner’s response, explain mistakes respectfully and adapt the difficulty of the next activity.

6\. Record practice activity and evidence of progress, then provide an appropriate summary to the learner and, where enabled, the teacher/guardian.

## Initial subjects and capabilities

- Start with a limited set of curriculum-mapped subjects, such as mathematics, science and reading/writing, rather than claiming support for every subject at launch.

- Support text-based tutoring first; add voice interaction and visual problem-solving after the core learning quality is validated.

- Support Amharic and English first for validated content, then add Afaan Oromo, Tigrinya and other languages only after testing educational accuracy and terminology.

- Offer guided practice, quizzes, worked examples, revision sessions and exam preparation.

- Provide a teacher/guardian view of activity, learning objectives practised and areas recommended for human review.

## Learning quality and boundaries

- Ground explanations and assessments in approved curriculum content and reviewed learning resources where possible.

- Distinguish sourced curriculum content from AI-generated explanations and clearly communicate uncertainty.

- Do not complete graded work deceptively on behalf of a learner; teach, guide, provide hints and support independent effort.

- Do not present the AI as a human teacher, counsellor or safeguarding professional.

- Provide age-appropriate content controls, session limits, reporting, parent/guardian settings and escalation paths.

- Use human-reviewed benchmark questions and language-specific evaluation before enabling a subject/language combination for children.

# 131. Consent, Safeguarding & AI Governance

> **Revised (26 November 2026, D34). The child-safeguarding and consent parts stand. The AI parts apply only if the deferred school AI assistant (§134.9) is built; recording and transcription consents are unused (D33).**

Safeguarding must be a cross-platform capability, not only a moderation screen. It applies to classroom communication, media, student accounts, portfolios, tutoring, live video, AI features and support operations.

## Child and guardian safeguards

- Define age-aware account and messaging rules, with school-managed student accounts where children do not have independent contact details.

- Verify guardian relationships through school-approved invitation or verification workflows; support multiple guardians and permission changes.

- Restrict adult-to-child direct messaging by default. Where permitted, use guardian visibility, school policy, auditability and clear reporting controls.

- Provide report, block, escalation and incident-management workflows for students, parents, teachers and tutors.

- Require identity and qualification verification for tutors and configure any additional checks according to the laws and requirements of each country.

- Use controlled media publishing and approval workflows for student photos, videos, portfolios and public-facing content.

## Consent and data governance

- Capture and version consent for account participation, media publication, live-session recording, transcription, AI processing and optional research/analytics separately where appropriate.

- Make recording and transcription opt-in where required; provide a visible indicator and a way to stop capture.

- Define retention periods for recordings, transcripts, AI outputs, student records and support evidence. Provide deletion, export and legal-hold processes.

- Minimise personal data sent to AI providers; avoid sending unnecessary identifiers and sensitive school records.

- Encrypt data in transit and at rest, restrict administrative access, audit sensitive reads/exports and review third-party data processing terms.

- Do not use children’s content to train external models unless there is a separately lawful, transparent and appropriately consented basis.

## AI oversight

- Require human review for consequential educational assessments, tutor reports, safeguarding decisions and content shared with parents.

- Maintain AI provider/model configuration, approved use cases, prompt/version records, evaluation results and incident handling.

- Provide a mechanism to flag harmful, biased, inaccurate or age-inappropriate AI output and suspend affected features or language/subject combinations.

- Do not use facial/emotion inference, covert surveillance or automated high-stakes decisions about children.

Country-specific privacy, child-protection, education and consumer requirements must be reviewed before launch in each market. The platform should support configurable policies rather than assuming one global legal rule.

# 132. Updated Domain and Technical Boundaries

> **Revised (26 November 2026, D32, D34): `tutoring_insights`, `ai.tutor_copilot` and `ai.student_tutor` are removed; a `tutors` (directory) and a `fees` boundary are added.**

Retain the modular monolith and the shared infrastructure described in the original document. Add the following first-class domain boundaries rather than scattering their rules across generic school, marketplace or AI services:

- curriculum: education systems, curriculum versions, subjects, topics, objectives and assessment rubrics.

- portfolio: portfolio collections, items, attachments, teacher feedback, approvals and visibility.

- school_communications: school announcements, audience scopes, schedules, delivery status and acknowledgements.

- sis_integration: import jobs, mapping profiles, external identifiers, sync runs and reconciliation reports.

- tutoring_insights: tutor operational metrics, learner progress summaries and reviewed tutor reports.

- ai.tutor_copilot: tutor lesson preparation, session assistance, summaries and review workflow.

- ai.student_tutor: student conversational sessions, learning activities, attempts, hints and progress evidence.

- consent_safeguarding: guardian consent, policy versions, reports, incidents, safety actions and audit records.

These domains may reuse identity, authorization, media, messaging, calendar, notification, payments, video and background-job infrastructure. They must retain their own business rules and access checks.

# 133. Release Gates and Success Measures

Use measurable pilot gates before expanding scope or opening a new country. Suggested measures include:

- School adoption: number of active pilot schools, weekly active teachers, classes created and roster import success rate.

- Family engagement: guardian link completion, announcement delivery/read rates, parent message response and active family participation.

- Classroom operations: attendance completion time, offline sync success, assignment submission and teacher tool usage.

- Learning: assignment completion, curriculum objective coverage, assessment reliability and portfolio participation.

- Tutoring: tutor verification completion, trial-to-follow-up conversion, session attendance, cancellations and parent satisfaction.

- AI quality: correctness on reviewed benchmark questions, language-specific quality, tutor acceptance/edit rates, learner outcomes and cost per completed task.

- Safety: consent coverage, report response time, moderation backlog, unauthorised access incidents and data-retention compliance.

Do not expand AI to additional subjects or languages solely because a model can produce text in that language. Require reviewed curriculum alignment, acceptable educational quality and functioning safeguarding controls first.

# 134. School Operations Update — Timetable, Assessment, Attendance Methods, Fees and School Assistant

*Added 26 November 2026. Decisions D14–D40 in [Spec_Review.md](Spec_Review.md); schema in [Database_Design.md](Database_Design.md) §4.30–§4.37 (§4.37 deferred); delivery in [Implementation_Plan.md](Implementation_Plan.md) (M7d, M7e, M22, M31a–d, M35, M37, M39–M42). Revised the same day by §135 and D32–D40.*

This section completes the school-operations scope of the platform. It adds to Sections 13–15, 24–27, 60, 62 and 126 and follows the same principles: server-side authorization, tenant isolation, consent for children's data, country configuration, and no feature that depends on one payment provider, one grading scheme or one national format. **HR and payroll (employee records beyond school staff membership, leave, payroll, tax, pension and payslips) are explicitly out of scope** (D14).

Every capability below is delivered with its security requirements. The requirements are part of the capability, not a later hardening step: a milestone is not done until the allow/deny tests, the abuse-case tests and the audit events listed for it pass.

## 134.1 Timetable and annual academic plan

- **Bell schedule and periods** per school or campus; **rooms**; per-class **subject period requirements** (periods per week, maximum per day, double periods); **teacher, room and class constraints** (unavailable, preferred, maximum per day, no consecutive).
- **Weekly timetables**, built by hand in a grid editor with a **coverage view** (periods placed against the weekly periods each class needs per subject). **An automatic generator is deferred** (D40) until a school with many classes asks for it.
- **Clash-free scheduling:** a class, a teacher or a room can be in one place at a time. This is enforced by the database for every version, so hand edits and the generator are both bound.
- **Versioned publishing:** a published version is immutable; a change is a new version with an effective date, or an override for one date (substitution, cancelled lesson, room change). Teachers, students and guardians see the published version in the calendar.
- **Annual academic plan:** terms, exam windows, holidays, activities, deadlines and meetings for the year, publishable to the school calendar.
- **Security:** only timetable managers build and publish; guardians see the timetable of their child's class only; change notifications say "lesson changed" and never why.

## 134.2 Lesson planning

- **Annual, term (scheme of work), weekly and daily lesson plans**, chained from one level to the next.
- **Ministry of Education format and other formats as templates:** each template lists the required fields, so a country or school format is configuration, not code. The platform ships a generic template; official formats are added as templates once supplied.
- **Timetable-driven planning:** daily plan skeletons for a week are created from the published timetable and linked to their lesson.
- **Review workflow:** draft, submit, approve or request changes, with revisions kept. The reviewer is never the author. Reviewers are the class's lead teacher, heads of department and holders of the review permission.
- Plans are tagged to curriculum objectives (§127).
- **Security:** plans are **staff-only**; students and guardians never read them; a plan is about a class and a topic, with no student reference.

## 134.3 Assessment, results, report cards and transcripts

- **Continuous assessment and assessment plans:** for each subject, class and period a plan defines the components (quizzes, tests, assignments, midterm, final, project, participation), their weights (adding to 100), maximum marks and drop-lowest rules.
- **Mark entry and subject marklists:** a gradebook grid with keyboard entry, limited to the subject's teachers. A marklist is submitted, optionally approved, then published to the child and guardians.
- **Corrections after publication** are recorded as amendments with a reason and a second approver, and the guardian is told that a result changed.
- **Semester or term results** (total, average, grade, conduct, comments, promotion decision at year end) and **student ranking** under the school's rank-visibility setting (§134.3.1).
- **Report cards:** generated automatically from a country or school template, issued as an immutable document with a verification code, in the family's language where translated.
- **Transcripts:** the cumulative record of this school's own published results across years, signed by a second person, issued for a transfer, a scholarship or the family's own copy. The platform holds no results from previous schools (D39).

### 134.3.1 Ranking and verification rules
- Rank visibility is a school setting: none, staff only, or **own rank only**. Families never see a list of students by rank, and rank is never sent in a notification. The Ethiopia configuration defaults to own rank only, because Ethiopian report cards print it.
- Every issued document carries a QR code that opens a public page showing only the document type, the school, the issue date and whether it is valid, revoked or replaced. The page never shows a name, marks or rank. It is rate-limited and answers unknown and malformed codes identically.
- **Records are not withheld because of unpaid fees** unless a school deliberately turns that on; the choice is audited.
- **Security:** unpublished marks are visible to the subject's teachers and grading managers only; every change after submission is an audited amendment; documents are generated from a snapshot so a reprint years later is identical; files are served through the owning record's access rule and never through a cached link.

## 134.4 Question banks, online exams and secure exams

- **Question banks** organised by subject and grade, tagged to curriculum objectives, with difficulty, language, approval and versions. Visibility: private, subject team or school.
- **Online examinations** built from the shared quiz engine (§25): time limits, attempts, auto-grading of objective questions, manual grading of essays, results release by the teacher or at close. Questions are **copied** into an exam so later bank edits never change an exam already taken.
- **Secure online exams:** a scheduled window, a server-side timer, one active attempt and device, per-student shuffling, an optional access code and school-network allowlist, extra time for learners who need it, and **integrity signals** for the teacher (focus lost, offline, device change).
- **Homework, assignment submission, marking and grading** are as in Sections 24–26.
- **Security:** correct answers never reach a student or guardian client before release; the exam paper is confidential before it opens; no cross-school sharing of banks; **no camera, microphone or screen proctoring** and no automatic penalty from signals (children's images and audio are not collected for exams); accommodation reasons are stored as codes and read only by the staff who need them.

## 134.5 Attendance: methods, reports and staff attendance

- **Methods**, chosen per school and per class. **Only manual marking and QR are supported (D38):**
  - **Manual** marking (the existing roll), always available.
  - **Rotating QR:** the teacher's screen shows a code that changes every few seconds; the student's app scans it.
  - **Student QR:** a card or badge with a QR that the teacher's phone scans (the code can be static).
- **Not supported:** NFC, RFID, GPS or any location check-in, kiosks and gate readers. No location is requested or stored anywhere in attendance.
- **Every method feeds the same roll**, so the lock window, school-local date, class membership on the date and the conflict rules are identical. A scan can mark a student present or late, never absent, and never overwrites a different mark.
- **Reports:** daily class and school summaries, **per-period** summaries (from timetable-driven rolls), student history, absence and lateness patterns, and **teacher and staff attendance** (daily and monthly), all exportable as audited CSV.
- **Staff attendance** uses the same two QR methods (a manager shows a rotating code for the campus and day, or scans the staff member's card) and manual marking; it is visible to the staff member and to school managers only, and has no leave or payroll link.
- **Security:** attendance credentials are separate from login codes, random, free of personal data, stored only as hashes, and revocable; a lost card is revoked and reissued. Known limits are stated to schools: a rotating code can be photographed and shared, and a static card can be copied. The short window, the teacher's live list and the teacher's ability to correct the roll are the mitigations.

## 134.6 Student identity numbers, ID cards and transferred status

- **Student ID generation:** a per-school format (prefix, year, zero-padded sequence, optional check digit), allocated without gaps or reuse, with a bulk run for existing students. The number is an identifier, **not a credential**, and is never used in a URL.
- **ID cards** for students and staff from a template (photo, name, class, validity, QR code), printed in batches, revocable, reissued on loss.
- **Student transfer is a status only (D39).** A school administrator marks a student **Transferred** with the date they left. Nothing moves between schools: there is no request, approval, consent, records package or prior-school record, and the destination is not recorded. The student's class memberships end, login codes, attendance credentials and ID cards are revoked, and guardians and teachers see "Transferred". The student's global identity and the guardian links stay, so a receiving school can add the same child without a duplicate profile. An administrator can undo it. The family can ask the school for a **transcript** (§134.3), which covers the school's own results; unpaid fees never block a transfer.

## 134.7 School fees

The fee requirement (D37):
1. a school sets up **periodic payment**: fee structures (items, each with a billing cycle: once, monthly, termly or yearly), assignment to students, optional items such as transport and meals, instalments and discounts, and a **billing schedule**; the platform then **issues invoices automatically** on the schedule;
2. guardians **pay in the app** through the country's payment provider (§61); or
3. guardians **pay through their bank or wallet and submit the transaction reference**, and the platform **verifies it through a payment-verification service** (a third-party lookup of Ethiopian bank and wallet receipts: **Veritas**, https://veritas.et, chosen by the owner).

Also in scope:
- **Cash payment recording** and cheque and deposit-slip recording by the bursar, with a second person's verification where required and a signed-off closing for every cashier session; a one-person school may use a single-person mode that is flagged in the audit.
- **Digital receipts** numbered without gaps per school and year, immutable, with **QR receipt verification** (the public page shows school, receipt number, date, amount and status, never a name).
- **Reports:** arrears and ageing, collection summary, cash book, receipts register, claims log and student statements.
- **Out of scope:** a double-entry ledger or accounting books, period closing, payroll, tax, purchasing, and provider refunds (a payment returned to a payer is a reversal recorded by finance and settled outside the platform). HR and payroll (D14).
- **Security:**
  - Financial records are **never edited**: a mistake is a reversal plus a new entry.
  - A payment is never marked paid by the client. An in-app payment is confirmed only by a verified, signed notification and a server-side status check that agree with the invoice on amount, currency and reference.
  - **An externally verified payment is confirmed only if** the receiver account matches the school's own registered account, the currency and amount agree, the date is sane, and the reference has never been used on the platform; anything doubtful goes to a person. The verification service receives **only the transaction reference and the one extra identifier a method needs** (an account suffix, or a phone number for CBE Birr), never a child's name or any student data; its key is a secret and each school's lookups are capped per month; its answers are parsed strictly (a missing field means a person decides); lookups run in the background and can take up to a minute; and a person can always confirm by hand if the service is down. The service is in Beta and publishes no SLA or privacy terms, so its terms are checked before launch.
  - Known limit, documented: a verification service proves that a transfer exists, not whose it is, so claims are rate-limited, the provider's masked payer name is shown to the reviewer, finance gets a daily digest of confirmed claims, and a family can dispute a claim on their reference.
  - Segregation of duties (configurable, with the single-person mode above): the person who records is not the person who verifies, voids or signs off; activating a billing schedule needs a second person; a cashier cannot void.
  - Guardians see fees only for their own child, with an active, unrestricted link; teachers and students never see balances, and no arrears appear in any class view.
  - Reminders carry the school name and due date only, never an amount or a child's name, and are never sent to a student account. The hardship reason of a discount is visible to finance managers only. Receiving-account numbers are encrypted and shown as the last four digits.

## 134.8 Communication and learning materials (confirmation)

The following are already specified and scheduled; this update records them against the owner's list so that none is lost:
- **Communication and messaging:** school-wide and class announcements (§28), teacher–parent, direct and group messaging (§29–31), in-app, push, SMS and email notifications (§93, §125), multilingual messages and translation (§32, §71), parents' preferred language (§111) and the school communication feed (§16, §125).
- **Learning materials:** shared classroom resources through the digital library (§33–35) and class work through assignments (§24). **Online courses and recorded content are not built (D33, §135).** A student's progress is their assignments, assessments, points and portfolio (§27).
- **Assignments and grading:** §24–27.

## 134.9 School AI assistant (deferred)

**Deferred on 26 November 2026 (D34): no milestone is scheduled and nothing of it is built.** The design below is kept for when the owner decides to build it; it also depends on an AI platform that does not exist in this version.

An assistant for parents, teachers and administrators that would answer questions **from the school's own records and documents** rather than from generic knowledge.
- **Parents** would ask about their own child's attendance, results, timetable, assignments, events and fees; **teachers** about their classes and timetable; **administrators** about school information and documents. It would also answer questions about the school's own handbook, policies, fee schedule and calendar.
- **How it would work:** no copy of the records. It calls fixed **read-only tools** that run as the signed-in user and apply the same access rules as the app, and it searches only the school's published, non-personal documents. Answers cite their source and say when nothing was found; it never guesses a number.
- **It adds no power:** it cannot show anything the user could not open in the app (health data and absence reasons, safeguarding records, messages, other children, rosters, restricted guardian links, exam content before release), and in the first version it cannot change anything.
- **Governance:** off by default, enabled per school, subject to AI consent (§131), minimised data to the model provider, a legal decision on where children's data may be processed, language-by-language quality gates, usage limits and a kill switch. Conversations are encrypted and expire; administrators cannot read other users' conversations. Students are not an audience.

## 134.10 Phasing and release gates

| Capability | Phase (see the Implementation Plan) |
|---|---|
| Per-period attendance, staff attendance, attendance reports | Phase 1 extension (M7d) |
| QR check-in | Phase 1 extension (M7e) |
| Student numbers, ID cards, transferred status; timetable and annual plan; lesson planning | Phase 2b (M35, M37, M39) |
| Assessment plans, marklists, results, ranking, report cards and transcripts; question banks and secure exams | Phase 2 and 2b (M18, M40, M41, M42) |
| Payment core; periodic fee invoicing, in-app payment, cash, receipts, payment verification, fee reports | Phase 3 (M22, M31a–d) |
| Tutor directory | Phase 4 (M25–M27, §135) |
| School AI assistant | **Deferred** (no milestone) |

Release gates (additions to §133): attendance by scan accepted without roll conflicts in the pilot; a pilot school's timetable published with every class's weekly periods covered; report cards issued with zero post-publication discrepancies; payment reconciliation with zero unexplained differences for a full term; a verified external payment confirmed without a person in the large majority of claims, with zero confirmed against the wrong school's account; tutor listings with zero contact details exposed in search or profile payloads.

# 135. Scope Revision — Tutor Directory and Removed Scope

*Added 26 November 2026. Decisions D32–D40 in [Spec_Review.md](Spec_Review.md); schema in [Database_Design.md](Database_Design.md) §4.24–§4.29 and §4.34–§4.36; delivery in [Implementation_Plan.md](Implementation_Plan.md) (Phases 3 to 5 and "Removed and deferred scope"). Where this section conflicts with an earlier one, this section wins.*

The product is a **school platform** (classroom, families, school operations, fees) plus a **tutor directory**. It is not a marketplace for online courses or recorded content, and it does not host lessons, run an AI tutor or process money on behalf of tutors.

## 135.1 Tutor directory

- **Tutors list themselves.** A tutor profile has a headline, bio, years of experience, subjects and levels, languages, a delivery mode (in person, or online with the tutor's own tools), **area-level locations** (city, sub-city or woreda, never an address), a **weekly availability pattern** shown as information (nothing is reserved), an informational rate, and a verified badge.
- **Families find tutors.** **Verified adults** (a phone verified by OTP and a date of birth showing 18 or over, or a guardian link verified by a school) can search by subject, level, language, area or city, in-person or online, price range, day of the week and verified status. **Student accounts have no directory.** Search and profiles never show a phone number, e-mail address, exact address or document.
- **Families message tutors** through the platform: a verified adult starts a conversation, for a child or for themself (no school link is needed), the tutor replies, and either side can block or report. **Students under 18 never contact a tutor** (owner decision), and a tutor can never start a conversation. The tutor sees the family's role label ("Parent" or "Learner"), the child's **age band** and the subject, never a name, school or photo unless the guardian writes it. Rules and limits: Messaging_Rules R14.
- **Verification before listing.** A platform review checks identity and qualifications (and, where the country requires it, police clearance) and the listing text and photo. Documents are private to platform reviewers.
- **No bookings, sessions, lesson payments, reviews, ratings, commissions or payouts.** Lessons are arranged between the family and the tutor outside the platform, and the product says so plainly. Reviews can be reconsidered later; nothing in the platform proves that a lesson happened, so ratings would not be trustworthy.
- **No school data for tutors.** A tutor never sees grades, attendance, portfolios, class membership or school messages, and a school never sees a family's tutor inquiries (§85, §103).

## 135.2 Listing fee

- Tutors **pay a listing subscription** to be listed, through the payment core (§61): a plan priced per country, billed monthly or yearly, with an optional trial.
- A tutor is **visible only while verified, listed and subscribed**. After a missed payment there is a 7-day grace, then the listing is hidden and the data is kept; paying again relists it without a new review unless the profile text or photo changed.
- **Paying buys a listing, not rank.** Results are ordered by relevance to the filters and by recent activity, the factors are shown, and there is no paid placement or opaque score.
- The platform takes no commission and no part in lesson payments.

## 135.3 Child safety in the directory

- Lessons happen off the platform, so the family is told plainly, on the listing and before the first message, that the platform does not run, supervise or guarantee lessons, that a guardian should be present or meet the tutor first, and that meetings should be in a public place or at home with an adult present.
- Anyone can report a tutor or a message to **platform moderators** (never to a school); a confirmed case suspends the listing at once and closes open conversations. Rate limits and review flags apply to a tutor who contacts many families of minors, and to messages that try to move a child off the platform.
- The terms (tutor terms, family notice) need legal review before launch. A public, searchable list of people who teach children is a safeguarding decision as well as a product one.

## 135.4 Removed and deferred scope

| Item | Status |
|---|---|
| Online courses, course sections, lessons, enrollments, live sessions, recordings, transcripts, hosted video (§36–§47, §56, §57) | **Removed** |
| Tutor services, bookings, sessions, trials, reviews, matching, tutor insights, commissions, payouts, lesson payments (§50–§52, §54, §55, §58, §59, §128) | **Removed** |
| Student AI Tutor and Tutor Copilot (§129, §130) and the AI platform built for them | **Removed** |
| School AI assistant (§134.9) | **Deferred**, no milestone |
| NFC, RFID, GPS check-in, kiosks and gate readers | **Removed**: attendance is manual and QR |
| Student transfer workflow, records package, prior-school records | **Removed**: transfer is a status |
| Automatic timetable generator | **Deferred** until a school with many classes asks |
| Double-entry ledger, financial periods, trial balance | **Removed** (D15, D37) |
| HR and payroll | **Out of scope** (D14) |

## 135.5 Phasing

Phase 3 is **payments and school fees** (payment core, periodic invoicing, in-app payment, cash and receipts, payment verification, fee reports). Phase 4 is the **tutor directory** (verification, search, inquiries, paid listing). Phase 5 is **platform billing and reconciliation**. There is no online-learning phase and no AI phase.
