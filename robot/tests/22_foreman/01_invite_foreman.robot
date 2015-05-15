*** Settings ***

Documentation   Mikko creates a new application
Resource        ../../common_resource.robot
Resource        keywords.robot

*** Test Cases ***

Mikko creates new application
  Mikko logs in
  ${secs} =  Get Time  epoch
  Set Suite Variable  ${appname}  foreman-app${secs}
  Create application the fast way  ${appname}  753-416-25-22  kerrostalo-rivitalo
  ${newApplicationid} =  Get Text  xpath=//span[@data-test-id='application-id']
  Set Suite Variable  ${newApplicationid}  ${newApplicationid}

Mikko invites foreman to application
  Open tab  parties
  Click by test id  invite-foreman-button
  Input Text  invite-foreman-email  teppo@example.com
  Click by test id  application-invite-foreman
  Wait until  Click by test id  application-invite-foreman-close-dialog
  Wait until  Element should be visible  //section[@id='application']//span[@data-test-operation-id='tyonjohtajan-nimeaminen-v2']
  ${foremanAppId} =  Get Text  xpath=//section[@id='application']//span[@data-test-id='application-id']
  Set Suite Variable  ${foremanAppId}  ${foremanAppId}

Mikko sees sent invitation on the original application
  Click by test id  test-application-link-permit-lupapistetunnus
  Wait until  Element text should be  xpath=//span[@data-test-id='application-id']  ${newApplicationid}
  Open tab  parties
  Wait until  Element text should be  xpath=//ul[@data-test-id='invited-foremans']//span[@data-test-id='foreman-email']  (teppo@example.com)
  [Teardown]  logout

Foreman can see application
  Teppo logs in
  Go to page  applications
  # Should work always because latest application is always at the top
  Wait until  Element text should be  xpath=//table[@id='applications-list']//tr[@data-test-address='${appname}'][1]/td[@data-test-col-name='operation']  Työnjohtajan nimeäminen
  Wait until  Element text should be  xpath=//table[@id='applications-list']//tr[@data-test-address='${appname}'][2]/td[@data-test-col-name='operation']  Asuinkerrostalon tai rivitalon rakentaminen
  [Teardown]  logout

Application is submitted
  Mikko logs in
  Open application at index  ${appname}  753-416-25-22  2
  Element should contain  xpath=//*[@data-test-id='test-application-operation']  Asuinkerrostalon tai rivitalon rakentaminen
  Submit application
  [Teardown]  logout

Application is approved and given a verdict
  Sonja logs in
  Open application at index  ${appname}  753-416-25-22  1
  Element should contain  xpath=//*[@data-test-id='test-application-operation']  Asuinkerrostalon tai rivitalon rakentaminen
  Click enabled by test id  approve-application
  Open tab  verdict
  Submit empty verdict

Add työnjohtaja task to original application
  Add työnjohtaja task to current application  Ylitarkastaja
  Add työnjohtaja task to current application  Alitarkastaja
  Wait until  Xpath Should Match X Times  //table[@data-test-id="tasks-foreman"]/tbody/tr  2
  [Teardown]  logout

Mikko can link existing foreman application to foreman task
  Mikko logs in
  Open application at index  ${appname}  753-416-25-22  1
  Open tab  tasks
  Select From List By Value  foreman-selection  ${foremanAppId}

Mikko can start invite flow from tasks tab
  Click enabled by test id  invite-other-foreman-button
  Wait until  Element should be visible  //div[@id='dialog-invite-foreman']
  Click by test id  cancel-foreman-dialog
  Click enabled by test id  invite-substitute-foreman-button
  Wait until  Element should be visible  //div[@id='dialog-invite-foreman']
  Click by test id  cancel-foreman-dialog

Mikko can invite additional foremans to application with verdict
  Wait and click   xpath=//table[@data-test-id='tasks-foreman']//tr[@data-test-name='Alitarkastaja']/td[@data-test-col-name='foreman-name-or-invite']/a
  Wait until  Element should be visible  invite-foreman-email
  Input Text  invite-foreman-email  teppo@example.com
  Click by test id  application-invite-foreman
  Wait until  Click by test id  application-invite-foreman-close-dialog
  Wait until  Element should be visible  //section[@id='application']//span[@data-test-operation-id='tyonjohtajan-nimeaminen-v2']
  [Teardown]  logout
