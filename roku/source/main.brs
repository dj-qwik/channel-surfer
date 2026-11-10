' Channel Surfer receiver for Roku.
' The phone app launches this channel over Roku's External Control Protocol (ECP):
'   POST /launch/dev?url=...&pos=...   (first time)
'   POST /input?url=...&pos=...        (while running: next program, channel change, etc.)
' Each command carries the live position, so the Roku joins the broadcast mid-program like real TV.
sub Main(args as Dynamic)
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.setMessagePort(port)
    scene = screen.CreateScene("MainScene")
    screen.show()

    input = CreateObject("roInput")
    input.setMessagePort(port)

    if args <> invalid and (args.url <> invalid or args.slate <> invalid or args.qreset <> invalid) then scene.cmd = args

    while true
        msg = wait(0, port)
        t = type(msg)
        if t = "roSGScreenEvent"
            if msg.isScreenClosed() then return
        else if t = "roInputEvent"
            info = msg.GetInfo()
            if info <> invalid then scene.cmd = info
        end if
    end while
end sub
