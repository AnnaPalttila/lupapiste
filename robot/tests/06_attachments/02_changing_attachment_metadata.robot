*** Settings ***

Documentation  Sonja should see only applications from Sipoo
Resource       ../../common_resource.robot
Variables      variables.py

*** Test Cases ***

Mikko creates application
  Mikko logs in
  Create application the fast way  application-papplication  753  753-416-25-30  asuinrakennus

Mikko edits operation description
  Open application  application-papplication  753-416-25-30
  Wait and click  xpath=//div[@id='application-info-tab']//span[@data-test-id='edit-op-description']
  Input text by test id  op-description-editor  Talo A
  Wait until  Page should contain  Tallennettu

Mikko adds an operation
  Set animations off
  Click enabled by test id  add-operation
  Wait Until  Element Should Be Visible  add-operation
  Wait and click  //section[@id="add-operation"]//div[@class="tree-content"]//*[text()="Rakentaminen ja purkaminen (talot, grillikatokset, autotallit, remontointi)"]
  Wait and click  //section[@id="add-operation"]//div[@class="tree-content"]//*[text()="Uuden rakennuksen rakentaminen (mökit, omakotitalot, saunat, julkiset rakennukset)"]
  Wait and click  //section[@id="add-operation"]//div[@class="tree-content"]//*[text()="Muun rakennuksen rakentaminen"]
  Wait until  Element should be visible  xpath=//section[@id="add-operation"]//div[@class="tree-content"]//*[@data-test-id="add-operation-to-application"]
  Click enabled by test id  add-operation-to-application

Mikko edits operation B description
  Wait and click  xpath=(//div[@id='application-info-tab']//span[@data-test-id='edit-op-description'])[last()]
  Execute Javascript  $("input[data-test-id='op-description-editor']:last").val("Talo B").change().blur();
  Wait until  Page should contain  Tallennettu

Mikko adds txt attachment without comment
  [Tags]  attachments
  ${secs} =  Get Time  epoch
  Set Suite Variable  ${appname}  attachments${secs}
  Open tab  attachments
  Add attachment  ${TXT_TESTFILE_PATH}  ${EMPTY}
  Application state should be  draft
  Wait Until  Element should be visible  xpath=//div[@data-test-id='application-pre-attachments-table']//a[contains(., '${TXT_TESTFILE_NAME}')]

Mikko opens attachment details
  [Tags]  attachments
  Open attachment details  muut.muu

Mikko can change related operation
  Element should be visible  xpath=//select[@data-test-id='attachment-operation-select']
  Select From List  xpath=//select[@data-test-id='attachment-operation-select']  Muun rakennuksen rakentaminen - Talo B

Mikko can change size
  Element should be visible  xpath=//select[@data-test-id='attachment-size-select']
  Select From List  xpath=//select[@data-test-id='attachment-size-select']  B0

Mikko can change scale
  Element should be visible  xpath=//select[@data-test-id='attachment-scale-select']
  Select From List  xpath=//select[@data-test-id='attachment-scale-select']  1:200

Mikko can change contents
  Element should be visible  xpath=//input[@data-test-id='attachment-contents-input']
  Input text by test id  attachment-contents-input  PuuCee
  Sleep  1
  [Teardown]  logout

Mikko logs in and goes to attachments tab
  Mikko logs in
  Open application  application-papplication  753-416-25-30
  Open tab  attachments

Mikko sees that contents metadata is visible in attachments list
  Element Text Should Be  xpath=//div[@id="application-attachments-tab"]//span[@data-test-id="attachment-contents"]  PuuCee

Mikko sees that attachments are grouped by operations
  Xpath Should Match X Times  //div[@id="application-attachments-tab"]//tr[@class="attachment-group-header"]  2

Mikko sees that his attachment is grouped by "Muun rakennuksen rakentaminen - Talo B" operation
  Element Text Should Be  xpath=(//div[@id="application-attachments-tab"]//tr[@class="attachment-group-header"])[last()]//td[@data-test-id="attachment-group-header-text"]  Muun rakennuksen rakentaminen - Talo B

Mikko opens attachment and sees that attachment label metadata is set
  Open attachment details  muut.muu
  Page should contain  Muun rakennuksen rakentaminen
  Page should contain  B0
  Textfield Value Should Be  xpath=//input[@data-test-id='attachment-contents-input']  PuuCee
  Page should contain  1:200
