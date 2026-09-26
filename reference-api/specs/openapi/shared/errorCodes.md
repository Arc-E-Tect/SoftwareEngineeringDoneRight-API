#### The below table define the Error Codes that this API may return.

  <table>
      <tr>
        <td><b>Error Code</b></td>
        <td><b>Summary</b></td>
        <td><b>Description</b></td>
      </tr>
       <tr>
        <td>400</td>
        <td>Bad Request</td>
        <td>The request is invalid</td>
      </tr>
       <tr>
        <td>401</td>
        <td>Unauthorized</td>
        <td>Requires authentication.</td>
      </tr>
       <tr>
        <td>403</td>
        <td>Forbidden</td>
        <td>You are not authorized to access the API. See the API's documentation to understand who is authorized to call the API.</td>
      </tr>
       <tr>
        <td>404</td>
        <td>Not Found</td>
        <td>The requested resource could not be found</td>
      </tr>
       <tr>
        <td>409</td>
        <td>Conflict</td>
        <td>There is a conflict in the request. The resource most likely exists</td>
      </tr>
      <tr>
        <td>422</td>
        <td>Unprocessable Content</td>
        <td>The request is not processable or there is a some validation problem with request</td>
      </tr>
      <tr>
      <tr>
        <td>429</td>
        <td>Too Many Requests</td>
        <td>Too many requests hit the API too quickly. We recommend an exponential backoff of your requests</td>
      </tr>
      <tr>
        <td>500, 502, 503, 504</td>
        <td>Server Errors</td>
        <td>Something went wrong on our end. (These are rare.)</td>
      </tr>
  </table>
