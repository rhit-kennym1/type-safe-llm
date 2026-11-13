# 6.4.2 Stakeholder Needs and Requirements Definition process
1. Prepare for Stakeholder Needs and Requirements Definition
a. Identify the stakeholders who have an interest in the software system throughout its lifecycle.
Outcome: a

XP implementation: XP handles this through the Whole Team and Real Customer Involvement practices. Instead of producing a stakeholder register, XP puts the people who matter to the system (customers, users, product managers, testers, and others) on the team and in the room. Beck's chapter on the Whole XP Team lists those roles and expects them to take part in development as it happens, not just at the start.

Supporting quote(s):

“Make people whose lives and business are affected by your system part of the team.” Chapter 9 
b. Define the stakeholder needs and requirements definition strategy.
Outcome: d

XP implementation: XP's strategy for requirements is a fixed one: write requirements as Stories, plan them in the Quarterly Cycle and Weekly Cycle, and pick up each week's stories from the customer. Because the strategy is the same for every project, XP doesn't write a separate strategy document. The planning practices are the strategy.

Supporting quote(s):

“Plan work a week at a time.” Chapter 7
c. Identify and plan for the necessary enabling systems or services needed to support stakeholder needs and requirements definition.
Outcome: h

XP implementation: The enabling "systems" in XP are simple and physical: a shared space (Sit Together), story cards, and a wall where everyone can see plans and progress (Informative Workspace). Planning for them means arranging the room and the board before the first planning meeting.

Supporting quote(s):

“Develop in an open space big enough for the whole team.” Chapter 7
d. Obtain or acquire access to the enabling systems or services to be used.
Outcome: h

XP implementation: XP's main enabling resource is access to the customer. Real Customer Involvement says the people whose needs the system serves should be reachable by the team. Teams also need the workspace and tooling (story board, shared repo) in place before the first iteration. XP doesn't say much more than that here, so this is a task where our tailoring is light.

Supporting quote(s):

“Make your workspace about your work.” Chapter 9
2. Define stakeholder needs
a. Define context of use within the concept of operations and the preliminary life cycle concepts.
Outcome: b

XP implementation: XP sets the context in the Quarterly Cycle, where the team reflects on the big picture: the themes for the quarter and how the project fits into the larger goals of the organization. Context also comes from the customer and interaction designers on the Whole Team, who bring knowledge of how and where the system will be used.

Supporting quote(s):

“Plan work a quarter at a time.”  Chapter 7
b. Identify stakeholder needs.
Outcome: d

XP implementation: Needs are captured as Stories: short units of customer-visible functionality written on cards, with a name and a short description. The customer writes or agrees to these stories, and new stories can be added at any time as needs become clear.

Supporting quote(s):
“Plan using units of customer-visible functionality.” Chapter 7
c. Prioritize and down-select needs.
Outcome: e

XP implementation: Prioritization happens in the planning game. Programmers estimate each story and the customer chooses which stories go into the quarter and the week, based on business value and estimated cost. Scope is the variable XP is willing to change, so down-selecting is a normal part of every cycle rather than a one-time event.

Supporting quote(s):

“Have the customers pick a week’s worth of stories to implement this week.” Chapter 7
d. Define the stakeholder needs and rationale.
Outcome: d

XP implementation: The story card holds the need, and the conversation between customer and team holds the rationale. XP prefers face-to-face Communication over written justification, so the rationale is mostly talked through during planning instead of written down. This is a gap compared to the standard. If our project needs recorded rationale, we can add a one-line "why" to each story.

Supporting quote(s):

“Communication is important for creating a sense of team and effective cooperation.” Chapter 4
3. Develop the operational concept and other life cycle concepts
a. Define a representative set of scenarios to identify all required capabilities that correspond to anticipated operational and other life cycle concepts.
Outcome: b

XP implementation: In XP, stories are the scenarios. Each story describes one thing a user does with the system, and the full set of stories for a release describes what the system must be able to do. Customer-written acceptance tests make each scenario specific enough to check.

Supporting quote(s):

“Write a failing automated test before changing any code.” Chapter 7
b. Identify the interaction between users and the system.
Outcome: b

XP implementation: Interaction designers are part of the Whole Team in XP. They work out how users will interact with the system, help write and clarify stories, and evaluate the system as it is deployed. Real Customer Involvement means real users can give feedback on those interactions early.

Supporting quote(s):

“Choose overall metaphors for the system, write stories, and evaluate usage of the deployed system”  Chapter 10
4. Transform stakeholder needs into stakeholder requirements
a. Identify the constraints on a system solution.
Outcome: c

XP implementation: XP surfaces constraints when stories are estimated and planned. Programmers raise technical constraints during estimation, and the customer brings business constraints such as time and budget. Negotiated Scope Contract treats time and cost as fixed and scope as negotiable, which makes those constraints explicit.

Supporting quote(s):

“Fix time, costs, and quality but call for an ongoing negotiation of the precise scope” Chapter 9
b. Identify the stakeholder requirements and functions that relate to critical quality characteristics, such as assurance, safety, security, environment, or health.
Outcome: e

XP implementation: XP treats quality as something that is never traded away (the Quality principle). Quality requirements are written as stories or as tests, and Test-First Programming with automated tests makes sure they keep holding. XP doesn't call out safety or security specifically, so this is a place where we would add explicit stories for those concerns.

Supporting quote(s):

“Sacrificing quality is not effective as a means of control.” Chapter 5
c. Define stakeholder requirements, consistent with life cycle concepts, scenarios, interactions, constraints, and critical quality characteristics.
Outcome: e

XP implementation: A story turns into a requirement when it has an estimate and acceptance tests that the customer agrees with. Writing automated tests before the code means each requirement is defined precisely enough to be verified.

Supporting quote(s):

“As soon as a story is written, try to estimate the development effort necessary to implement it.” Chapter 7
5. Analyze stakeholder requirements
a. Analyze the complete set of stakeholder requirements.
Outcome: e

XP implementation: XP analyzes the full set of stories in the Quarterly Cycle and narrows it in the Weekly Cycle. The team looks for stories that are too large, unclear, or dependent on each other and splits or rewrites them. XP does this analysis a little at a time, not up front for the whole system.

Supporting quote(s):

“Pick a quarter’s worth of stories to address those themes.” Chapter 7
b. Define critical performance measures that enable the assessment of technical achievement.
Outcome: f

XP implementation: XP's performance measures are visible and simple: stories completed per week, passing tests, and build health. Plans and progress go on the wall in the Informative Workspace, and the Ten-Minute Build and Continuous Integration give constant feedback on whether the system works. Performance requirements for the software itself (speed, load) are written as stories with tests.

Supporting quote(s):

“Automatically build the whole system and run all of the tests in ten minutes.” Chapter 7
c. Feed back the analyzed requirements to applicable stakeholders to validate that their needs and expectations have been adequately captured and expressed.
Outcome: g

XP implementation: XP's feedback loop is short: each week the customer sees working software and the acceptance tests that pass. Incremental Deployment and Real Customer Involvement mean stakeholders check the requirements against a running system instead of a document. Feedback is one of XP's core values.

Supporting quote(s):

“No fixed direction remains valid for long” Chapter 4
d. Resolve stakeholder requirements issues.
Outcome: g

XP implementation: Because the customer is on the team (Sit Together, Whole Team), requirement questions are answered in conversation, usually the same day. Larger disagreements are settled in the next weekly planning meeting by rewriting or reprioritizing stories. Root-Cause Analysis applies when a requirements problem keeps coming back.

Supporting quote(s):

“Every time a defect is found after development, eliminate the defect and its cause.” Chapter 9
6. Manage the stakeholder needs and requirements definition
a. Obtain explicit agreement on the stakeholder requirements.
Outcome: g

XP implementation: The customer's choice of stories in each planning cycle is the explicit agreement. Acceptance tests written or approved by the customer are the agreement on what "done" means for each story.

Supporting quote(s):

“Every activity should benefit all concerned.” Chapter 5
b. Maintain traceability of stakeholder needs and requirements.
Outcome: i

XP implementation: XP keeps traceability through Code and Tests: the stories link to acceptance tests, and the tests link to the code that passes them. A Single Code Base keeps that link in one place. XP doesn't keep a formal traceability matrix, so this is a point where we may tailor the standard by linking story IDs to commits and tests in our repo.

Supporting quote(s):

“Maintain only the code and the tests as permanent artifacts.” Chapter 9
c. Provide key artifacts and information items that have been selected for baselines.
Outcome: i

XP implementation: XP keeps very few permanent artifacts: the code and the tests. Code and Tests says those are what to maintain, and everything else (story cards, plans) can be regenerated. The baseline is whatever is in the single code base with all tests passing, and Daily Deployment keeps that baseline current.

Supporting quote(s):

“There is only one code stream.” Chapter 9

# 6.4.4

# 6.4.5 Design Definition process
1. Prepare for software system design definition

a. Define the design definition strategy.

Outcome: f

XP implementation: XP does not try to completely design the system before implementation begins. The design develops incrementally as stories are selected and implemented. The Weekly Cycle gives the team a short planning horizon, while the design is refined as the team learns more about the system.

Supporting quote(s):

“Plan work a week at a time.” Chapter 7

b. Select and prioritize design principles and design characteristics.

Outcome: a

XP implementation: XP emphasizes keeping the design simple and maintaining quality while the system changes. Design choices should support the current stories rather than trying to predict every future requirement. The team can refactor the design later when new requirements make a change necessary.

Supporting quote(s):

“No fixed direction remains valid for long.” Chapter 4

c. Identify and plan for the systems or services needed to support design.

Outcome: g

XP implementation: XP depends on development tools that allow the team to design and test continuously, such as the shared repository, automated tests, build system, development environment, and collaboration tools. These are prepared so programmers can design, implement, and test changes throughout each iteration.

Supporting quote(s):

“Automatically build the whole system and run all of the tests in ten minutes.” Chapter 7

d. Obtain access to the systems or services needed for design.

Outcome: g

XP implementation: The team needs access to the shared code base, build tools, testing tools, development environments, and other resources before doing the work. XP's Single Code Base practice also makes sure the team works from one shared version of the software instead of separate disconnected copies.

Supporting quote(s):

“There is only one code stream.” Chapter 9

2. Establish designs for each software system element

a. Turn architectural and design characteristics into designs for individual software elements.

Outcome: b

XP implementation: XP turns larger requirements into small stories and then implements the design needed for those stories. Instead of fully specifying every component beforehand, programmers design the pieces needed for the current story and continuously improve how those pieces fit together.

Supporting quote(s):

“Plan using units of customer-visible functionality.” Chapter 7

b. Define or obtain the necessary design enablers.

Outcome: c

XP implementation: XP uses tools such as tests, coding conventions, stories, design patterns, and simple diagrams when they help the team implement the software. Automated tests are especially important because they give programmers feedback while the design changes.

Supporting quote(s):

“Write a failing automated test before changing any code.” Chapter 7

c. Examine design alternatives and determine whether they are practical to implement.

Outcome: e

XP implementation: XP encourages developers to try simple solutions and learn from implementation rather than deciding every technical detail in advance. If an approach becomes difficult or does not work well, the team can change the design through refactoring and choose a better solution.

Supporting quote(s):

“No fixed direction remains valid for long.” Chapter 4

d. Refine the interfaces between software elements and external systems.

Outcome: d

XP implementation: Interfaces are refined while stories are implemented and components begin interacting with each other. Continuous communication between team members helps developers recognize when an interface is confusing or difficult to use and change it before more code depends on it.

Supporting quote(s):

“Communication is important for creating a sense of team and effective cooperation.” Chapter 4

e. Establish the design artifacts.

Outcome: f

XP implementation: XP tries to avoid creating documentation that will quickly become outdated. The most important permanent design artifacts are the working code and automated tests. Other diagrams or design documents can still be created when they are useful, but XP prefers artifacts that remain connected to the actual implementation.

Supporting quote(s):

“Maintain only the code and the tests as permanent artifacts.” Chapter 9

3. Assess alternatives for obtaining software system elements

a. Determine the technologies required for each software element.

Outcome: c

XP implementation: Technology choices are made based on what is needed to implement the current requirements. The team can investigate libraries, frameworks, languages, providers, or tools and select technologies that allow the stories to be implemented while maintaining quality.

Supporting quote(s):

“Sacrificing quality is not effective as a means of control.” Chapter 5

b. Identify possible alternatives for software system elements.

Outcome: e

XP implementation: The team can consider building something themselves, reusing existing code, using an open-source library, or using an external service. XP does not require every technical decision to be fixed at the beginning, so alternatives can be investigated as the relevant story approaches implementation.

Supporting quote(s):

“No fixed direction remains valid for long.” Chapter 4

c. Evaluate the candidate alternatives against the required characteristics.

Outcome: e

XP implementation: XP favors getting feedback from working software. Candidate solutions can be tested through small implementations, automated tests, or prototypes instead of selecting an option only from documentation. The team can compare whether each alternative actually satisfies the required behavior and quality.

Supporting quote(s):

“Write a failing automated test before changing any code.” Chapter 7

d. Choose the preferred design alternative.

Outcome: e

XP implementation: After trying or evaluating the alternatives, the team chooses the option that best supports the current requirements while keeping the design simple and maintainable. The decision does not have to be permanent because XP allows the design to evolve when new information becomes available.

Supporting quote(s):

“No fixed direction remains valid for long.” Chapter 4

4. Manage the design

a. Capture the design and the reasoning behind important decisions.

Outcome: f

XP implementation: XP captures most of the detailed design directly in the code and tests rather than creating large design documents. Important decisions can still be recorded when the reasoning will be useful to the team, but documentation should support the software instead of becoming a separate version of the design.

Supporting quote(s):

“Maintain only the code and the tests as permanent artifacts.” Chapter 9

b. Maintain traceability between the detailed design, requirements, and architecture.

Outcome: h

XP implementation: Stories describe the requirements, tests verify those stories, and the code implements the behavior checked by the tests. Keeping all of these in a single code base gives the team a practical form of traceability. If more formal traceability is needed, story IDs can also be connected to tests, commits, or pull requests.

Supporting quote(s):

“There is only one code stream.” Chapter 9

c. Determine the current status of the software design.

Outcome: a

XP implementation: XP measures the state of the design continuously rather than waiting for a formal design review. Automated tests, continuous integration, and working software show whether the current design still supports the requirements. When tests fail or the design becomes difficult to change, the team can refactor it.

Supporting quote(s):

“Automatically build the whole system and run all of the tests in ten minutes.” Chapter 7
