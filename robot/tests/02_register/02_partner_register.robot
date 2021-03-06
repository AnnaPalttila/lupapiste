*** Settings ***

Documentation   Identity federation
Suite Teardown  Logout
Resource        ../../common_resource.robot
Resource        ../common_keywords/vetuma_helpers.robot

*** Test Cases ***

Setup random email
  [Tags]  integration  ie8
  ${secs} =  Get Time  epoch
  Set Suite Variable  ${email}  ${secs}@example.com

Send mock identity to server
  [Tags]  integration  ie8
  Go to  ${SERVER}/dev-pages/idf.html
  Execute Javascript  $("input[name='email']").val("${email}").change();
  Wait until  Page should contain  READY
  Click element  submit

Got email
  [Tags]  integration  ie8
  Open last email
  Wait Until  Page Should Contain  ${email}
  Page Should Contain  /app/fi/welcome#!/link-account
  ## Click the first link
  Click link  xpath=//a
  Register button is visible

Federated user activates account via VETUMA
  [Tags]  integration  ie8
  Authenticate via Osuuspankki via Vetuma  vetuma-linking-init
  Wait until  Submit is disabled
  Wait until  Textfield should contain  xpath=//input[@data-test-id='link-account-street']  Sepänkatu 11 A 5
  Textfield should contain  xpath=//input[@data-test-id='link-account-zip']  70100
  Textfield should contain  xpath=//input[@data-test-id='link-account-city']  KUOPIO
  Textfield should contain  xpath=//input[@data-test-id='link-account-phone']  040

  Input text by test id  link-account-password  vetuma69
  Submit is disabled

  Input text by test id  link-account-confirmPassword  vetuma68
  Submit is disabled

  Input text by test id  link-account-confirmPassword  vetuma69
  Submit is disabled

  Checkbox Should Not Be Selected  linkAccountAllowDirectMarketing
  Select Checkbox  linkAccountAllowDirectMarketing
  Checkbox Should Be Selected  linkAccountAllowDirectMarketing

  Checkbox Should Not Be Selected  linkAccountAcceptTerms
  Select Checkbox  linkAccountAcceptTerms
  Checkbox Should Be Selected  linkAccountAcceptTerms
  Click enabled by test id  link-account-submit

Federated user lands to empty applications page
  [Tags]  integration  ie8
  User should be logged in  Sylvi Sofie Marttila
  Confirm notification dialog
  Applications page should be open
  Number of visible applications  0


*** Keywords ***

Submit is disabled
  ${path} =   Set Variable  xpath=//button[@data-test-id='link-account-submit']
  Wait Until  Element Should Be Disabled  ${path}

Register button is visible
  Wait until page contains element  vetuma-linking-init
