*** Settings ***

Documentation   Inforequest state handling
Suite teardown  Logout
Resource        ../../common_resource.robot

*** Test Cases ***

Mikko creates two new inforequests
  Mikko logs in
  ${secs} =  Get Time  epoch
  Set Suite Variable  ${inforequest-handling}  ir-h${secs}
  Set Suite Variable  ${inforequest-cancelling}  ir-c${secs}
  Set Suite Variable  ${newName}  ${inforequest-cancelling}-edit
  Set Suite Variable  ${propertyId}  753-416-25-30
  Create inforequest the fast way  ${inforequest-handling}  753  ${propertyId}  asuinrakennus  Jiihaa
  Create inforequest the fast way  ${inforequest-cancelling}  753  ${propertyId}  asuinrakennus  Jiihaa
  Logout

Authority assigns an inforequest to herself
  Sonja logs in
  Inforequest is not assigned  ${inforequest-handling}
  Open inforequest  ${inforequest-handling}  ${propertyId}
  Wait until  Element should be visible  inforequest-assignee-select
  Select From List  inforequest-assignee-select  777777777777777777000023
  Element should be visible  //*[@data-test-id='inforequest-cancel-btn']

Now Sonja is marked as authority
  Go to page  applications
  Inforequest is assigned to  ${inforequest-handling}  Sonja Sibbo
  Logout

Mikko sees Sonja as authority
  Mikko logs in
  Inforequest is assigned to  ${inforequest-handling}  Sonja Sibbo

Mikko should be able to cancel the inforequest but not mark it as answered
  Open inforequest  ${inforequest-handling}  ${propertyId}
  Element should not be visible  //*[@data-test-id='inforequest-mark-answered']
  Element should be visible  //*[@data-test-id='inforequest-cancel-btn']

Mikko should be able to add attachment
  Element should be visible  //*[@data-test-id='add-inforequest-attachment']

Mikko opens inforequest for renaming and cancellation
  Open inforequest  ${inforequest-cancelling}  ${propertyId}

Mikko changes inforequest address
  Page should contain  ${inforequest-cancelling}
  Page should not contain  ${newName}
  Element should be visible  xpath=//section[@id='inforequest']//a[@data-test-id='change-location-link']
  Click element  xpath=//section[@id='inforequest']//a[@data-test-id='change-location-link']
  Textfield Value Should Be  xpath=//input[@data-test-id="application-new-address"]  ${inforequest-cancelling}
  Input text by test id  application-new-address  ${newName}
  Click enabled by test id  change-location-save
  Wait Until  Page should contain  ${newName}

Mikko cancels an inforequest
  Wait Until  Element should be enabled  xpath=//*[@data-test-id='inforequest-cancel-btn']
  Click enabled by test id  inforequest-cancel-btn
  Confirm  dynamic-yes-no-confirm-dialog

Mikko does not see the cancelled inforequest
  Wait until  Element should be visible  applications-list
  Wait Until  Inforequest is not visible  ${inforequest-cancelling}
  Wait Until  Inforequest is not visible  ${newName}

Mikko waits until the first inforequest is answered
  Logout

Authority can not convert the inforequest to application
  Sonja logs in
  Open inforequest  ${inforequest-handling}  ${propertyId}
  Wait until  Inforequest state is  Avoin
  Element should not be visible  //*[@data-test-id='inforequest-convert-to-application']

Authority adds a comment marking inforequest answered
  Wait until  Page should contain element  //section[@id='inforequest']//button[@data-test-id='comment-request-mark-answered']
  Add comment and Mark answered  oletko miettinyt tuulivoimaa?
  Wait until  Inforequest state is  Vastattu
  Logout

Mikko sees the inforequest answered
  Mikko logs in
  Open inforequest  ${inforequest-handling}  ${propertyId}
  Wait until  Inforequest state is  Vastattu

Mikko should still be able to add attachment
  Element should be visible  //*[@data-test-id='add-inforequest-attachment']

When Mikko adds a comment inforequest goes back to Avoin
  Add comment   tuulivoima on ok.
  Wait until  Inforequest state is  Avoin
  Logout

Authority cancels the inforequest
  Sonja logs in
  Open inforequest  ${inforequest-handling}  ${propertyId}
  Wait Until  Element should be enabled  xpath=//*[@data-test-id='inforequest-cancel-btn']
  Click enabled by test id  inforequest-cancel-btn
  Confirm  dynamic-yes-no-confirm-dialog


*** Keywords ***

Inforequest state is
  [Arguments]  ${state}
  Wait until   Element Text Should Be  test-inforequest-state  ${state}

Inforequest is not visible
  [Arguments]  ${address}
  Wait until  Page Should Not Contain Element  xpath=//table[@id='applications-list']//tr[@data-test-address='${address}']

Inforequest is not assigned
  [Arguments]  ${address}
  Wait until  Element text should be  xpath=//table[@id='applications-list']//tr[@data-test-address='${address}']//td[@data-test-col-name='authority']  ${EMPTY}

Inforequest is assigned to
  [Arguments]  ${address}  ${name}
  Wait until  Element text should be  xpath=//table[@id='applications-list']//tr[@data-test-address='${address}']//td[@data-test-col-name='authority']  ${name}

Add Comment
  [Arguments]  ${message}
  Input comment  inforequest  ${message}

Add Comment and Mark Answered
  [Arguments]  ${message}
  Input comment and mark answered  inforequest  ${message}
