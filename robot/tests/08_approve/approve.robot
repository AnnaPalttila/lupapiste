*** Settings ***

Documentation   Mikko can't approve application
Resource        ../../common_resource.robot

*** Test Cases ***

Mikko creates an application
  Mikko logs in
  ${secs} =  Get Time  epoch
  Set Suite Variable  ${appname}  approve-app${secs}
  Create application the fast way  ${appname}  753  753-416-25-30
  Execute Javascript  $("textarea[name='kuvaus']").val('Hieno hanke...').change();
  Execute Javascript  $("textarea[name='poikkeamat']").val('poikkeuksetta!').change();

Mikko can't approve application
  Wait Until  Element should be disabled  xpath=//*[@data-test-id='approve-application']

Mikko decides to submit application
  Submit application

Mikko still can't approve application
  Wait Until  Element should be disabled  xpath=//*[@data-test-id='approve-application']
  [Teardown]  logout

Sonja logs in for approval
  Sonja logs in
  Open application  ${appname}  753-416-25-30

Sonja approves application
  Click enabled by test id  approve-application

Sonja cant re-approve application
  Wait Until  Element should be disabled  xpath=//*[@data-test-id='approve-application']

Sonja sees that some completion is needed
  Open application  ${appname}  753-416-25-30
  Click enabled by test id  request-for-complement
  Wait Until  Application state should be  complement-needed
  [Teardown]  logout

Mikko comes back, fills in missing parts and makes a resubmit
  Mikko logs in
  Open application  ${appname}  753-416-25-30
  Submit application
  [Teardown]  logout
