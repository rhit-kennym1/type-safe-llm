6.4.2 Stakeholder Needs and Requirements Definition process
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
