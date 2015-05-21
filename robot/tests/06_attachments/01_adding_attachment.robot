*** Settings ***

Documentation  Mikko adds an attachment
Suite setup     Apply minimal fixture now
Suite teardown  Logout
Resource       ../../common_resource.robot
Variables      variables.py

*** Test Cases ***

Mikko goes to empty attachments tab
  [Tags]  attachments
  ${secs} =  Get Time  epoch
  Set Suite Variable  ${appname}  attachments${secs}
  Mikko logs in
  Create application the fast way  ${appname}  753-416-25-30  kerrostalo-rivitalo
  Open tab  attachments

"Download all attachments" should not be visible in the attachment actions dropdown
  Page should not contain element  xpath=//select[@data-test-id="attachment-operations-select-lower"]//option[@value='downloadAll']

Dropdown options for attachment actions should look correct for Mikko
  [Tags]  attachments
  Page should not contain element  xpath=//select[@data-test-id="attachment-operations-select-lower"]//option[@value='newAttachmentTemplates']
  Page should not contain element  xpath=//select[@data-test-id="attachment-operations-select-lower"]//option[@value='attachmentsMoveToBackingSystem']

Mikko adds txt attachment without comment
  [Tags]  attachments
  Add attachment  ${TXT_TESTFILE_PATH}  ${EMPTY}  Asuinkerrostalon tai rivitalon rakentaminen
  Application state should be  draft
  Wait Until  Element should be visible  xpath=//div[@data-test-id='application-pre-attachments-table']//a[contains(., '${TXT_TESTFILE_NAME}')]

Mikko deletes attachment immediately by using remove icon
  [Tags]  attachments
  Click element  xpath=//div[@id="application-attachments-tab"]//span[@data-test-icon="delete-muut.muu"]
  Confirm yes no dialog
  Wait Until  Element should not be visible  xpath=//div[@data-test-id='application-pre-attachments-table']//a[contains(., '${TXT_TESTFILE_NAME}')]

Mikko adds again txt attachment without comment
  [Tags]  attachments
  Add attachment  ${TXT_TESTFILE_PATH}  ${EMPTY}  Asuinkerrostalon tai rivitalon rakentaminen
  Application state should be  draft
  Wait Until  Element should be visible  xpath=//div[@data-test-id='application-pre-attachments-table']//a[contains(., '${TXT_TESTFILE_NAME}')]

"Download all attachments" should be visible in the attachment actions dropdown
  Page should contain element  xpath=//select[@data-test-id="attachment-operations-select-lower"]//option[@value='downloadAll']

Mikko opens attachment details
  [Tags]  attachments
  Open attachment details  muut.muu

Mikko does not see Reject-button
  [Tags]  attachments
  Element should not be visible  test-attachment-reject

Mikko does not see Approve-button
  [Tags]  attachments
  Element should not be visible  test-attachment-approve

Mikko deletes attachment
  [Tags]  attachments
  Click enabled by test id  delete-attachment
  Confirm yes no dialog
  Wait Until Page Contains  753-416-25-30
  Wait Until  Page Should Not Contain  xpath=//a[@data-test-type="muut.muu"]

Mikko adds txt attachment with comment
  [Tags]  attachments
  Add attachment  ${TXT_TESTFILE_PATH}  ${TXT_TESTFILE_DESCRIPTION}  Asuinkerrostalon tai rivitalon rakentaminen

Mikko opens application to authorities
  Open to authorities  pliip
  Wait Until  Application state should be  open

Mikko see that attachment is for authority
  [Tags]  attachments
  Wait Until  Attachment state should be  muut.muu  requires_authority_action

Mikko adds comment
  [Tags]  attachments
  Open attachment details  muut.muu
  Input comment  mahtava liite!

Comment is added
  [Tags]  attachments
  Wait Until  Comment count is  2

Change attachment type
  [Tags]  attachments
  Click enabled by test id  change-attachment-type
  Select From List  attachment-type-select  rakennuspaikka.ote_alueen_peruskartasta
  Wait Until  Element Should Not Be Visible  attachment-type-select-loader
  Click enabled by test id  confirm-yes
  Click element  xpath=//a[@data-test-id="back-to-application-from-attachment"]
  Wait Until  Tab should be visible  attachments
  Page Should Not Contain  xpath=//a[@data-test-type="muut.muu"]

Signature icon is not visible
  Element should not be visible  xpath=//div[@id="application-attachments-tab"]//span[@data-test-icon="signed-rakennuspaikka.ote_alueen_peruskartasta"]

Sign all attachments
  [Tags]  attachments
  Tab should be visible  attachments
  Select attachment operation option from dropdown  signAttachments
  Wait Until   Element should be visible  signAttachmentPassword
  Input text by test id  signAttachmentPassword  mikko123
  Click enabled by test id  do-sign-attachments
  Wait Until   Element should not be visible  signAttachmentPassword
  Confirm  dynamic-ok-confirm-dialog

Signature icon is visible
  Wait Until  Element should be visible  xpath=//div[@id="application-attachments-tab"]//span[@data-test-icon="signed-rakennuspaikka.ote_alueen_peruskartasta"]

Signature is visible
  Open attachment details  rakennuspaikka.ote_alueen_peruskartasta
  Assert file latest version  ${TXT_TESTFILE_NAME}  1.0
  Wait Until  Xpath Should Match X Times  //section[@id="attachment"]//*/div[@data-bind="fullName: user"]  1
  Element text should be  xpath=//section[@id="attachment"]//*/div[@data-bind="fullName: user"]  Intonen Mikko
  Element text should be  xpath=//section[@id="attachment"]//*/span[@data-bind="version: version"]  1.0
  Element should be visible  xpath=//section[@id="attachment"]//*/div[@data-bind="dateTimeString: created"]

Sign single attachment
  Click enabled by test id  signLatestAttachmentVersion
  Wait Until   Element should be visible  signSingleAttachmentPassword
  Input text by test id  signSingleAttachmentPassword  mikko123
  Click enabled by test id  do-sign-attachment
  Wait Until   Element should not be visible  signSingleAttachmentPassword

Two signatures are visible
  Wait Until  Xpath Should Match X Times  //section[@id="attachment"]//*/div[@data-bind="fullName: user"]  2

Switch user
  [Tags]  attachments
  Logout
  Sonja logs in

Sonja goes to conversation tab
  [Tags]  attachments
  Open application  ${appname}  753-416-25-30
  Open side panel  conversation
  Click Element  link=Ote alueen peruskartasta
  Wait Until Page Contains  ${TXT_TESTFILE_NAME}
  Close side panel  conversation

Sonja goes to attachments tab
  [Tags]  attachments
  Wait Until  Element should be visible  xpath=//a[@data-test-id="back-to-application-from-attachment"]
  Click element  xpath=//a[@data-test-id="back-to-application-from-attachment"]
  Open tab  attachments

Sonja adds new attachment template
  Add empty attachment template  Muu liite  muut  muu

Sonja sees that new attachment template is visible in attachments list
  Wait Until Element Is Visible  xpath=//div[@id="application-attachments-tab"]//a[@data-test-type="muut.muu"]

Sonja deletes the newly created attachment template
  Click element  xpath=//div[@id="application-attachments-tab"]//span[@data-test-icon="delete-muut.muu"]
  Confirm yes no dialog
  Wait Until  Element should not be visible  xpath=//div[@data-test-id='application-pre-attachments-table']//a[@data-test-type="muut.muu"]

Sonja continues with Mikko's attachment. She sees that attachment is for authority
  [Tags]  attachments
  Wait Until  Attachment state should be  rakennuspaikka.ote_alueen_peruskartasta  requires_authority_action

Sonja opens attachment details
  [Tags]  attachments
  Open attachment details  rakennuspaikka.ote_alueen_peruskartasta

Sonja sees Reject-button which is enabled
  [Tags]  attachments
  Wait Until  Element should be visible  test-attachment-reject
  Element should be enabled  test-attachment-reject

Sonja sees Approve-button which is enabled
  [Tags]  attachments
  Wait until  Element should be visible  test-attachment-approve
  Element should be enabled  test-attachment-approve

Sonja rejects attachment
  [Tags]  attachments
  Element should be enabled  test-attachment-reject
  Click element  test-attachment-reject

Reject-button should be disabled
  [Tags]  attachments
  Wait until  Element should be disabled  test-attachment-reject

Sonja approves attachment
  [Tags]  attachments
  Wait until  Element should be enabled  test-attachment-approve
  Click element  test-attachment-approve

Approve-button should be disabled
  [Tags]  attachments
  Wait until  Element should be disabled  test-attachment-approve

Attachment state should be ok
  Click element  xpath=//a[@data-test-id="back-to-application-from-attachment"]
  Tab should be visible  attachments
  Wait Until  Attachment state should be  rakennuspaikka.ote_alueen_peruskartasta  ok

*** Keywords ***

Attachment state should be
  [Arguments]  ${type}  ${state}
  ## Fragile: assumes there is only one element that has data-test-state
  ${STATE_ATTR_VALUE} =  Get Element Attribute  xpath=//*[@data-test-state and @data-test-type="${type}"]@data-test-state
  Log  ${STATE_ATTR_VALUE}
  Should Be Equal  ${STATE_ATTR_VALUE}  ${state}

Comment count is
  [Arguments]  ${amount}
  Open side panel  conversation
  Xpath Should Match X Times  //div[@id='conversation-panel']//div[@data-bind='foreach: comments().slice(0).reverse()']/div  ${amount}
  Close side panel  conversation
