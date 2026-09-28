# The books stubs: the official WireMock image, with the mapping files and bodies
# from the archive packageBooksWiremock builds. booksStubsImage unpacks the archive
# beside this file and builds it; the version follows the one in the version catalog.
ARG WIREMOCK_VERSION=3.13.2
FROM wiremock/wiremock:${WIREMOCK_VERSION}
COPY mappings /home/wiremock/mappings
COPY __files /home/wiremock/__files
