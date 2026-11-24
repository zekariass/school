# Multi-Country Multi-Tenant Education, Community & Tutoring Platform
*Updated working specification — 20 November 2026. This revision preserves the original multi-country, multi-tenant architecture and adds product requirements for student portfolios, school-wide communications, SIS imports, teacher tools, curriculum alignment, tutor insights, AI Tutor Copilot, student-facing AI tutoring, and strengthened safeguarding. The revised phase descriptions in Sections 112–117 and the additions in Sections 123–133 form the updated working specification.*

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
Support:

ONE_TO_ONE

SMALL_GROUP

and:

ONLINE

IN_PERSON

The same tutor can offer multiple services.

# 53. Tutor Availability
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
Introduce structured online courses and learning content, separate from official school classes.

- Teacher/tutor course creator profiles and course ownership

- Pre-recorded, live and hybrid courses

- Sections, lessons, learning materials, quizzes and course assignments

- Course enrolment, approval, access control and progress tracking

- Course discussions and learner notifications

- Course pricing and payments where the local market is ready

# 115. Phase 4 — Tutor Marketplace
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

Complete the commercial operations needed to operate tutoring and paid learning reliably.

- Orders, payments, payment reconciliation, refunds and cancellation rules

- Platform commissions, tutor earnings, payout eligibility and payout records

- Separate school fee billing from tutor booking and course payment models

- Tutor dashboard: upcoming lessons, completed sessions, earnings, cancellations and attendance

- Tutor quality insights: verified reviews, punctuality, lesson completion and learner progress signals

- Parent-facing tutoring history, session notes and progress summaries

Avoid opaque tutor rankings; explain recommendation factors and allow parents to choose.

# 117. Phase 6 — AI Tutoring & Advanced Platform

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

Complex school ERP features

Start with strong boundaries inside a modular monolith.

# 120. Final Product Architecture
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

This update adds the capabilities described in Sections 124–133. It does not replace the original architecture. The original principles for global identity, multi-tenancy, explicit memberships, server-side authorization, separation of school and marketplace data, modular-monolith delivery and country configuration remain in force.

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
