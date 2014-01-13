*** Settings ***

Documentation   Authority admin creates users
Suite setup     Apply minimal fixture now
Suite teardown  Logout
Resource       ../../common_resource.robot

*** Test Cases ***

Authority admin goes to the authority admin page
  Sipoo logs in
  Wait until page contains  Organisaation viranomaiset

Authority admin creates two users
  Set Suite Variable  ${userRowXpath}  //div[@class='admin-users-table']//table/tbody/tr
  Wait Until  Element Should Be Visible  ${userRowXpath}
  ${userCount} =  Get Matching Xpath Count  ${userRowXpath}
  Create user  heikki.virtanen@example.com  Heikki  Virtanen
  Create user  hessu.kesa@example.com  Hessu  Kesa
  ${userCountAfter} =  Evaluate  ${userCount} + 2
  User count is  ${userCountAfter}

Authority admin removes Heikki
  ${userCount} =  Get Matching Xpath Count  ${userRowXpath}
  Element should be visible  xpath=//div[@class='admin-users-table']//tr[@data-user-email='heikki.virtanen@example.com']//a[@data-op='removeFromOrg']
  Click element  xpath=//div[@class='admin-users-table']//tr[@data-user-email='heikki.virtanen@example.com']//a[@data-op='removeFromOrg']
  Confirm  dynamic-yes-no-confirm-dialog
  ${userCountAfter} =  Evaluate  ${userCount} - 1
  User count is  ${userCountAfter}
  Page should not contain  heikki.virtanen@example.com
  Logout

Hessu activates account via email
  Go to  ${SERVER}/api/last-email
  Page Should Contain  hessu.kesa@example.com
  ## First link
  Click link  xpath=//a
  Fill in new password  hessu123

Hessu can login
  User logs in  hessu.kesa@example.com  hessu123  Hessu Kesa
  [Teardown]  Logout

Authority admin adds existing authority as a statement person
  Sipoo logs in
  Set Suite Variable  ${statementPersonRowXpath}  //tr[@class='statement-person-row']
  ${userCount} =  Get Matching Xpath Count  ${statementPersonRowXpath}
  Create statement person  ronja.sibbo@sipoo.fi  Asiantuntija
  ${userCountAfter} =  Evaluate  ${userCount} + 1
  Statement person count is  ${userCountAfter}
  Wait Until  Page should contain  Asiantuntija

*** Keywords ***

User count is
  [Arguments]  ${amount}
  Wait Until  Xpath Should Match X Times  ${userRowXpath}  ${amount}

Statement person count is
  [Arguments]  ${amount}
  Wait Until  Xpath Should Match X Times  ${statementPersonRowXpath}  ${amount}

Create user
  [Arguments]  ${email}  ${firstName}  ${lastName}
  Click enabled by test id  authadmin-add-authority
  Wait until  Element should be visible  //label[@for='auth-admin.admins.add.email']
  Input text  auth-admin.admins.add.email  ${email}
  Input text  auth-admin.admins.add.firstName  ${firstName}
  Input text  auth-admin.admins.add.lastName  ${lastName}
  Click enabled by test id  authadmin-add-authority-continue
  Click enabled by test id  authadmin-add-authority-ok
  Wait Until  Element Should Not Be Visible  add-user-to-organization-dialog
  Wait Until  Page Should Contain  ${email}

Create statement person
  [Arguments]  ${email}  ${text}
  Click enabled by test id  create-statement-person
  Wait until  Element should be visible  //label[@for='statement-person-email']
  Input text  statement-person-email  ${email}
  Input text  statement-person-text  ${text}
  Click enabled by test id  create-statement-person-save
