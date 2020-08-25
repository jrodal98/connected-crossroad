# Connected Crossroad

Connected Crossroad is an Android application that utilizes Google's Nearby Connections API
to implement Ad Hoc On-Demand Distance Vector (AODV) routing to establish a Mobile Ad Hoc Network
(MANET). The application can also integrate non-Android devices into the MANET via UDP.

### Using the Application

This application must run on a physical Android device, as it uses networking features which
cannot be emulated. Once the application has started the user must input an "address" for the
device, which must be a number. After the user clicks the button to set the address, the discovery
process of the routing protocol is initiated. The user can then send a message to another device
in the network by inputting the address of the device to send to and the message to transfer, and
then clicking the send message button. It is useful to view the application logs in Logcat to
understand what the app is doing.

### AODV Routing

Details about the AODV routing algorithm can be found in
[RFC 3561](https://tools.ietf.org/html/rfc3561).

### Non-Android Device Connections

An example of another AODV application that targeted a non-Android device, but connected with
Androids in this application via UDP is
[AODV MK6C](https://github.com/fdfea/aodv-mk6c).

### Future Development

This application is in active development at the University of Virginia as part of the "Exploring
Mobile Ad Hoc Networks (MANETs) to Enable Connected Transportation Services" research being
conducted in
[Dr. Haiying Shen's Pervasive Communications Laboratory](https://www.cs.virginia.edu/~hs6ms/research.htm).
