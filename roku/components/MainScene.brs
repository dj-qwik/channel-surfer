' Live-only playback: no pause, rewind or skip. The Roku keeps its own live clock from the
' position the phone sent, and quietly re-syncs if it drifts or someone presses pause.
'
' The phone also sends the channel's schedule for the next ~12 hours (a queue). The Roku walks that queue by
' the wall clock on its own, so the channel keeps playing show after show even when the phone is asleep.

sub init()
    m.video = m.top.findNode("video")
    m.slate = m.top.findNode("slate")
    m.slateTitle = m.top.findNode("slateTitle")
    m.slateText = m.top.findNode("slateText")
    m.banner = m.top.findNode("banner")
    m.toast = m.top.findNode("toast")
    m.bannerTimer = m.top.findNode("bannerTimer")
    m.toastTimer = m.top.findNode("toastTimer")
    m.liveTimer = m.top.findNode("liveTimer")

    ' Scale the system fonts up for a TV across the room.
    m.top.findNode("slateTitle").font.size = 64
    m.top.findNode("num").font.size = 80
    m.top.findNode("chName").font.size = 44
    m.top.findNode("progTitle").font.size = 36
    m.top.findNode("live").font.size = 30

    m.clock = CreateObject("roTimespan")
    m.url = ""
    m.basePos = 0.0
    m.baseMs = 0
    m.until = 0.0

    m.video.observeField("state", "onState")
    m.bannerTimer.observeField("fire", "hideBanner")
    m.toastTimer.observeField("fire", "hideToast")
    m.liveTimer.observeField("fire", "checkLive")
    m.liveTimer.control = "start"

    m.queue = []
    m.qBase = 0
    m.skew = 0.0
    m.curKey = ""
    m.tickTimer = m.top.findNode("tickTimer")
    m.tickTimer.observeField("fire", "tick")
    m.tickTimer.control = "start"

    ' Snow, hiss and the anti-burn-in drift.
    m.snowFrames = []
    for i = 0 to 5
        m.snowFrames.Push(m.top.findNode("snow" + i.ToStr()))
    end for
    m.snowIndex = 0
    m.msgBox = m.top.findNode("msgBox")
    m.saver = m.top.findNode("saver")
    m.saverLogo = m.top.findNode("saverLogo")
    m.hiss = m.top.findNode("hiss")
    m.hissTimer = m.top.findNode("hissTimer")
    m.hissTimer.observeField("fire", "replayHiss")
    m.retryTimer = m.top.findNode("retryTimer")
    m.retryTimer.observeField("fire", "retryProgram")
    m.errCount = 0
    m.lastKey = ""
    m.bannerTitle = ""
    m.snowTimer = m.top.findNode("snowTimer")
    m.snowTimer.observeField("fire", "nextSnow")
    m.driftTimer = m.top.findNode("driftTimer")
    m.driftTimer.observeField("fire", "drift")
    m.dx = 3
    m.dy = 2
    m.slateClock = CreateObject("roTimespan")
    m.top.findNode("introCh").font.size = 72
    startIntro()
    m.top.setFocus(true)
end sub

function livePos() as Float
    return m.basePos + (m.clock.TotalMilliseconds() - m.baseMs) / 1000.0
end function

sub onCmd()
    c = m.top.cmd
    if c = invalid then return

    if c.num <> invalid then m.top.findNode("num").text = c.num
    if c.ch <> invalid then m.top.findNode("chName").text = UCase(c.ch)
    if c.title <> invalid then m.top.findNode("progTitle").text = c.title

    if c.qreset <> invalid or c.qadd <> invalid
        takeQueue(c)
        return
    end if
    m.queue = []

    if c.url <> invalid and c.url <> ""
        m.basePos = 0.0
        if c.pos <> invalid then m.basePos = Val(c.pos)
        m.baseMs = m.clock.TotalMilliseconds()
        if c.url <> m.url or m.video.state = "stopped" or m.video.state = "error" or m.video.state = "finished"
            content = CreateObject("roSGNode", "ContentNode")
            content.url = c.url
            if c.fmt <> invalid then content.streamFormat = c.fmt else content.streamFormat = "mp4"
            if c.title <> invalid then content.title = c.title
            content.PlayStart = Int(livePos())
            m.url = c.url
            m.video.content = content
            m.video.control = "play"
        else
            m.video.seek = livePos()
        end if
        hideSlate()
    else
        m.video.control = "stop"
        m.url = ""
        title = "OFF AIR"
        if c.slate <> invalid then title = c.slate
        detail = ""
        if c.detail <> invalid then detail = c.detail
        showSlate(title, detail)
    end if
    showBanner()
end sub

' ---------------------------------------------------------------- schedule queue

' Seconds since the queue's base time, on the phone's clock (corrected for any difference between the clocks).
function nowRel() as Float
    dt = CreateObject("roDateTime")
    return (dt.AsSeconds() - m.qBase) + dt.GetMilliseconds() / 1000.0 + m.skew
end function

' Items arrive as i0..i9 = "start|end|offset|format|title|url" (seconds relative to base; "-" = empty).
sub takeQueue(c as Object)
    if c.qreset <> invalid
        m.queue = []
        m.curKey = ""
        if c.base <> invalid then m.qBase = c.base.ToInt()
        m.skew = 0.0
        if c.pnow <> invalid then m.skew = Val(c.pnow) - nowRel()
    end if
    for i = 0 to 9
        raw = c["i" + i.ToStr()]
        if raw <> invalid
            parts = []
            for each p in raw.Tokenize("|")
                parts.Push(p)
            end for
            if parts.Count() >= 6
                url = parts[5]
                for k = 6 to parts.Count() - 1
                    url = url + "|" + parts[k]
                end for
                m.queue.Push({ s: Val(parts[0]), e: Val(parts[1]), o: Val(parts[2]), f: parts[3], t: parts[4], u: url })
            end if
        end if
    end for
    tick()
end sub

sub tick()
    if m.introActive then return
    if m.queue.Count() = 0 then return
    now = nowRel()
    while m.queue.Count() > 0 and m.queue[0].e <= now
        m.queue.Shift()
    end while
    if m.queue.Count() = 0
        if m.curKey <> "end"
            m.curKey = "end"
            m.video.control = "stop"
            m.url = ""
            showSlate("NO SIGNAL", "Open Channel Surfer on your phone to keep watching")
        end if
        return
    end if
    it = m.queue[0]
    if it.s > now then return
    key = Str(it.s) + it.u
    if key = m.curKey then return
    if key <> m.lastKey then m.errCount = 0
    m.lastKey = key
    m.curKey = key
    m.top.findNode("progTitle").text = it.t
    if it.u = "-"
        m.video.control = "stop"
        m.url = ""
        if Left(UCase(it.t), 7) = "UP NEXT"
            showCard("COMING UP NEXT", Mid(it.t, 10))
        else
            showSlate(it.t, "We'll be right back")
        end if
    else
        m.basePos = it.o + (now - it.s)
        m.baseMs = m.clock.TotalMilliseconds()
        if it.u <> m.url or m.video.state = "stopped" or m.video.state = "error" or m.video.state = "finished" or m.video.state = "none"
            content = CreateObject("roSGNode", "ContentNode")
            content.url = it.u
            if it.f = "hls" then content.streamFormat = "hls" else content.streamFormat = "mp4"
            content.title = it.t
            content.PlayStart = Int(livePos())
            m.url = it.u
            m.video.content = content
            m.video.control = "play"
        else
            m.video.seek = livePos()
        end if
        hideSlate()
    end if
    ' Banner when a new show starts, not after every commercial break.
    if it.u <> "-" and it.t <> m.bannerTitle and it.e - it.s > 120
        m.bannerTitle = it.t
        showBanner()
    end if
end sub

' ---------------------------------------------------------------- power-on intro

sub startIntro()
    m.introActive = true
    m.intro = m.top.findNode("intro")
    m.introDot = m.top.findNode("introDot")
    m.introLine = m.top.findNode("introLine")
    m.introSnow = m.top.findNode("introSnow")
    m.introSnowFrames = [m.top.findNode("isnow0"), m.top.findNode("isnow1"), m.top.findNode("isnow2")]
    m.introSnowIndex = 0
    m.introLogo = m.top.findNode("introLogo")
    m.introScan = m.top.findNode("introScan")
    m.introCh = m.top.findNode("introCh")
    m.introClock = CreateObject("roTimespan")
    m.introSoundPlayed = false
    m.introTimer = m.top.findNode("introTimer")
    m.introTimer.observeField("fire", "introFrame")
    m.introTimer.control = "start"
end sub

function ramp(t as Float, a as Float, b as Float) as Float
    if t <= a then return 0.0
    if t >= b then return 1.0
    return (t - a) / (b - a)
end function

sub introFrame()
    t = m.introClock.TotalMilliseconds()
    if not m.introSoundPlayed and t >= 100
        m.introSoundPlayed = true
        m.top.findNode("powerOn").control = "play"
    end if

    ' 1) Warm-up dot
    if t >= 150 and t < 480
        p = ramp(t, 150, 450)
        d = 30 + 110 * p
        m.introDot.visible = true
        m.introDot.width = d
        m.introDot.height = d
        m.introDot.translation = [960 - d / 2, 540 - d / 2]
        m.introDot.opacity = 0.4 + 0.6 * p
    else
        m.introDot.visible = false
    end if

    ' 2) Line opens across, 3) snaps open to a full white flash, then fades
    if t >= 450 and t < 1700
        m.introLine.visible = true
        w = 1920 * (0.02 + 0.98 * ramp(t, 450, 950))
        q = ramp(t, 950, 1300)
        h = 6 + 1074 * q * q
        m.introLine.width = w
        m.introLine.height = h
        m.introLine.translation = [960 - w / 2, 540 - h / 2]
        m.introLine.opacity = 1.0 - ramp(t, 1300, 1700)
    else
        m.introLine.visible = false
    end if

    ' 4) Static while the tuner locks on
    if t >= 1200 and t < 2700
        m.introSnow.visible = true
        m.introSnow.opacity = ramp(t, 1200, 1350) * (1.0 - ramp(t, 2300, 2700))
        m.introSnowFrames[m.introSnowIndex].visible = false
        m.introSnowIndex = (m.introSnowIndex + 1 + Rnd(2)) mod 3
        m.introSnowFrames[m.introSnowIndex].visible = true
    else
        m.introSnow.visible = false
    end if
    m.introCh.visible = (t >= 1300 and t < 3300)

    ' 5) The welcome card rolls in (a little vertical hold wobble) and settles
    p = ramp(t, 2200, 2900)
    m.introLogo.opacity = p
    m.introLogo.translation = [0, (1 - p) * 90]
    m.introScan.opacity = p

    if t >= 3350 then endIntro()
end sub

sub endIntro()
    m.introTimer.control = "stop"
    m.intro.visible = false
    m.introActive = false
    if m.queue.Count() > 0
        tick()
    else if m.video.state <> "playing" and m.video.state <> "buffering"
        showSlate("NO SIGNAL", "Open Channel Surfer on your phone and tap CAST.")
    end if
end sub

' ---------------------------------------------------------------- snow / screensaver

' After this long on the "nothing on" screen, go quiet and dark (no hiss) so a sleeping viewer's TV is safe.
function SAVER_AFTER_MS() as Integer
    return 15 * 60 * 1000
end function

' A quiet "Coming up next" card between shows: no static, no hiss.
sub showCard(title as String, detail as String)
    m.slateTitle.text = title
    m.slateText.text = detail
    m.snowTimer.control = "stop"
    m.hissTimer.control = "stop"
    m.hiss.control = "stop"
    m.top.findNode("snow").visible = false
    m.saver.visible = false
    m.top.findNode("cardBg").visible = true
    m.msgBox.visible = true
    m.slate.visible = true
    m.driftTimer.control = "start"
end sub

sub showSlate(title as String, detail as String)
    m.slateTitle.text = title
    m.slateText.text = detail
    m.top.findNode("cardBg").visible = false
    if m.slate.visible and m.snowTimer.control = "start" then return
    m.slate.visible = true
    m.saver.visible = false
    m.top.findNode("snow").visible = true
    m.msgBox.visible = true
    m.slateClock.Mark()
    m.snowTimer.control = "start"
    m.driftTimer.control = "start"
    m.hiss.control = "play"
    m.hissTimer.control = "start"
end sub

sub replayHiss()
    m.hiss.control = "stop"
    m.hiss.control = "play"
end sub

' A program that failed to start gets a couple more tries before we give up until the next one.
sub retryProgram()
    if m.errCount > 3 then return
    m.curKey = ""
    m.url = ""
    tick()
end sub

sub hideSlate()
    m.slate.visible = false
    m.snowTimer.control = "stop"
    m.driftTimer.control = "stop"
    m.hissTimer.control = "stop"
    m.hiss.control = "stop"
end sub

sub nextSnow()
    m.snowFrames[m.snowIndex].visible = false
    ' Jump around rather than cycling in order, so the snow never looks like a loop.
    m.snowIndex = (m.snowIndex + 1 + Rnd(4)) mod 6
    m.snowFrames[m.snowIndex].visible = true
    if not m.saver.visible and m.slateClock.TotalMilliseconds() > SAVER_AFTER_MS()
        m.snowTimer.control = "stop"
        m.hissTimer.control = "stop"
        m.hiss.control = "stop"
        m.top.findNode("snow").visible = false
        m.msgBox.visible = false
        m.saver.visible = true
    end if
end sub

' Slowly bounce the message box (or the screensaver logo) around the screen, DVD-logo style.
sub drift()
    if m.saver.visible then node = m.saverLogo else node = m.msgBox
    w = node.width
    h = node.height
    t = node.translation
    x = t[0] + m.dx
    y = t[1] + m.dy
    if x < 40 or x + w > 1880 then m.dx = -m.dx
    if y < 40 or y + h > 1040 then m.dy = -m.dy
    node.translation = [x, y]
end sub

sub onState()
    st = m.video.state
    if st = "paused"
        ' Live TV can't be paused: resume and rejoin the live position.
        m.video.control = "resume"
        m.video.seek = livePos()
        showToast("LIVE TV")
    else if st = "error"
        showSlate("TECHNICAL DIFFICULTIES", "Please stand by")
        m.errCount = m.errCount + 1
        m.retryTimer.control = "stop"
        m.retryTimer.control = "start"
    else if st = "finished"
        showSlate("STATION BREAK", "We'll be right back")
    end if
end sub

sub checkLive()
    if m.video.state = "playing"
        drift = m.video.position - livePos()
        if drift > 5 or drift < -5 then m.video.seek = livePos()
    end if
end sub

sub showBanner()
    m.banner.visible = true
    m.bannerTimer.control = "stop"
    m.bannerTimer.control = "start"
end sub

sub hideBanner()
    m.banner.visible = false
end sub

sub showToast(text as String)
    m.toast.text = text
    m.toast.visible = true
    m.toastTimer.control = "stop"
    m.toastTimer.control = "start"
end sub

sub hideToast()
    m.toast.visible = false
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press then return false
    if key = "OK" or key = "info" or key = "up" or key = "down"
        showBanner()
        return true
    else if key = "play" or key = "fastforward" or key = "rewind" or key = "replay" or key = "left" or key = "right"
        showToast("LIVE TV - change channels on your phone")
        return true
    end if
    return false
end function
