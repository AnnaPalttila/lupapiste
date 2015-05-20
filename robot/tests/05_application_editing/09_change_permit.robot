*** Settings ***

Documentation   On R type of application, applying a change permit
Suite teardown  Logout
Resource        ../../common_resource.robot

*** Test Cases ***

Sonja prepares the application
  Sonja logs in
  ${secs} =  Get Time  epoch
  Set Suite Variable  ${appname}  Base_app_for_change_permit_${secs}
  Set Suite Variable  ${propertyid}  753-423-2-41
  Create application the fast way  ${appname}  ${propertyid}  kerrostalo-rivitalo

  Wait until  Application state should be  open
  ${applicationTitle} =  Get Text  xpath=//span[@data-test-id='application-title']
  Set Suite Variable  ${applicationTitle}
  ${applicationid} =  Get Text  xpath=//span[@data-test-id='application-id']
  Set Suite Variable  ${applicationid}

Sonja submits the application, approves it and gives it a verdict
  Submit application
  Element should be visible  xpath=//button[@data-test-id="approve-application-summaryTab-missing-required"]
  Element should be visible  xpath=//button[@data-test-id="approve-application"]
  Click enabled by test id  approve-application-summaryTab-missing-required
  Element should not be visible  xpath=//*[@data-test-id='change-permit-create-btn']
  Open tab  verdict
  Fetch verdict

Sonja creates a change permit
  Wait Until  Element should be visible  xpath=//*[@data-test-id='change-permit-create-btn']
  Element Text Should Be  xpath=//span[@data-test-id='application-id']  ${applicationid}
  Element Text Should Be  xpath=//span[@data-test-id='application-property-id']  ${propertyid}
  Element Should Be Visible  xpath=//span[@data-test-id='application-submitted-date']
  Element Should Not Be Visible  xpath=//*[@data-test-id='test-application-app-linking-to-us']

  # Create and open the change permit by pressing button
  Click enabled by test id  change-permit-create-btn
  Confirm  dynamic-yes-no-confirm-dialog

  Wait until  Application state should be  open
  Element Should Not Be Visible  xpath=//*[@data-test-id='change-permit-create-btn']

  ${newApplicationid} =  Get Text  xpath=//span[@data-test-id='application-id']
  Set Suite Variable  ${newApplicationid}
  Should Not Be Equal As Strings  ${newApplicationid}  ${applicationid}

  Element Text Should Be  xpath=//span[@data-test-id='application-title']  ${applicationTitle}
  Element Text Should Be  xpath=//span[@data-test-id='application-property-id']  ${propertyid}
  Element Should Not Be Visible  xpath=//span[@data-test-id='application-submitted-date']

  Element Should Not Be Visible  xpath=//*[@data-test-id='test-application-app-linking-to-us']
  Element Should Be Visible  xpath=//*[@data-test-id='test-application-link-permit-lupapistetunnus']
  Element Text Should Be  xpath=//*[@data-test-id='test-application-link-permit-lupapistetunnus']  ${applicationid}

  # Go to the link permit via link
  Click by test id  test-application-link-permit-lupapistetunnus

  Wait Until  Element should not be visible  xpath=//*[@data-test-id='test-application-link-permit-lupapistetunnus']
  Wait Until  Element should be visible  xpath=//*[@data-test-id='test-application-app-linking-to-us']
  Element Text Should Be  xpath=//*[@data-test-id='test-application-app-linking-to-us']  ${newApplicationid}

