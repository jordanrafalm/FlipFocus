const imgSignal = "https://www.figma.com/api/mcp/asset/e935bdda-bf18-45e0-a59d-c4539665c142.svg";
const imgWifi = "https://www.figma.com/api/mcp/asset/5665799e-0c5b-4a3d-86ca-8651518ab636.svg";
const imgIosBatteryFull = "https://www.figma.com/api/mcp/asset/b3a75b08-ebfb-4153-8339-5f2551da7147.svg";
const imgRingTrack = "https://www.figma.com/api/mcp/asset/6554d7a5-ec4d-4366-a6eb-d29b3e2d6b05.svg";
const imgProgressRing = "https://www.figma.com/api/mcp/asset/7de91bc2-c5d2-4d7a-8df3-f4a000e2cf94.svg";
const imgAlertCircle = "https://www.figma.com/api/mcp/asset/a1601416-58d2-43e7-ae3e-d6721b1a8079.svg";

export default function InterruptedState() {
  return (
    <div className="bg-[#0b0b0c] border border-[#2a1c09] border-solid content-stretch flex flex-col items-start overflow-clip relative rounded-[44px] size-full" data-node-id="2:478" data-name="Interrupted state">
      <div className="absolute inset-[-1px]" data-node-id="2:479" style={{ backgroundImage: "url(\"data:image/svg+xml;utf8,<svg viewBox='0 0 393 852' xmlns='http://www.w3.org/2000/svg' preserveAspectRatio='none'><rect x='0' y='0' height='100%' width='100%' fill='url(%23grad)' opacity='1'/><defs><radialGradient id='grad' gradientUnits='userSpaceOnUse' cx='0' cy='0' r='10' gradientTransform='matrix(17.685 0 0 38.34 196.5 408.96)'><stop stop-color='rgba(245,158,11,0.094118)' offset='0'/><stop stop-color='rgba(11,11,12,0)' offset='1'/></radialGradient></defs></svg>\")" }} data-name="Amber atmosphere" />
      <div className="content-stretch flex h-[48px] items-center justify-between overflow-clip px-[24px] relative shrink-0 w-full" data-node-id="2:480" data-name="Status bar">
        <p className="[word-break:break-word] font-['Inter:Regular'] font-normal leading-[normal] not-italic relative shrink-0 text-[#fafafa] text-[13px] whitespace-nowrap" data-node-id="2:481">
          9:41
        </p>
        <div className="content-stretch flex gap-[8px] items-center overflow-clip relative shrink-0" data-node-id="2:482" data-name="System status">
          <div className="h-[11px] relative shrink-0 w-[15px]" data-node-id="2:556" data-name="signal">
            <img alt="" className="absolute block inset-0 max-w-none size-full" src={imgSignal} />
          </div>
          <div className="h-[11px] relative shrink-0 w-[15px]" data-node-id="2:559" data-name="wifi">
            <img alt="" className="absolute block inset-0 max-w-none size-full" src={imgWifi} />
          </div>
          <div className="h-[10px] relative shrink-0 w-[20px]" data-node-id="2:562" data-name="ios-battery-full">
            <img alt="" className="absolute block inset-0 max-w-none size-full" src={imgIosBatteryFull} />
          </div>
        </div>
      </div>
      <div className="content-stretch flex h-[52px] items-center justify-between overflow-clip px-[24px] relative shrink-0 w-full" data-node-id="2:486" data-name="Header">
        <p className="[word-break:break-word] font-['Inter:Regular'] font-normal leading-[normal] not-italic relative shrink-0 text-[#fafafa] text-[20px] whitespace-nowrap" data-node-id="2:487">
          FlipFocus
        </p>
        <div className="bg-[#141416] border border-[#29292d] border-solid content-stretch flex items-start overflow-clip px-[12px] py-[8px] relative rounded-[999px] shrink-0" data-node-id="2:488" data-name="Streak">
          <p className="[word-break:break-word] font-['Inter:Regular'] font-normal leading-[normal] not-italic relative shrink-0 text-[#f59e0b] text-[11px] whitespace-nowrap" data-node-id="2:489">
            🔥 4 Days
          </p>
        </div>
      </div>
      <div className="content-stretch flex flex-[1_0_0] flex-col items-center justify-between min-h-px overflow-clip pb-[14px] pt-[22px] px-[24px] relative w-full" data-node-id="2:490" data-name="Paused content">
        <div className="content-stretch flex flex-col items-center justify-center overflow-clip relative shrink-0 size-[286px]" data-node-id="2:491" data-name="Timer">
          <div className="-translate-x-1/2 -translate-y-1/2 absolute left-1/2 size-[286px] top-1/2" data-node-id="2:492" data-name="Ring track">
            <img alt="" className="absolute block inset-0 max-w-none size-full" src={imgRingTrack} />
          </div>
          <div className="-translate-x-1/2 -translate-y-1/2 absolute left-1/2 size-[286px] top-1/2" data-node-id="2:493" data-name="Progress ring">
            <div className="absolute inset-[0_0_0_31.27%]">
              <img alt="" className="block max-w-none size-full" src={imgProgressRing} />
            </div>
          </div>
          <div className="[word-break:break-word] content-stretch flex flex-col gap-[4px] items-center leading-[normal] not-italic overflow-clip relative shrink-0 whitespace-nowrap" data-node-id="2:494" data-name="Countdown">
            <p className="font-['Inter:Light'] font-light relative shrink-0 text-[#fafafa] text-[58px]" data-node-id="2:495">
              14:22
            </p>
            <p className="font-['Inter:Medium'] font-medium relative shrink-0 text-[#f59e0b] text-[13px] uppercase" data-node-id="2:496">
              left
            </p>
          </div>
        </div>
        <div className="content-stretch flex flex-col gap-[12px] items-center relative shrink-0 w-full" data-node-id="2:497" data-name="Pause message">
          <div className="bg-[#2a1c09] content-stretch flex items-center justify-center overflow-clip relative rounded-[999px] shadow-[0px_0px_28px_2px_rgba(245,158,11,0.26)] shrink-0 size-[52px]" data-node-id="2:498" data-name="Alert badge">
            <div className="relative shrink-0 size-[22px]" data-node-id="2:565" data-name="alert-circle">
              <img alt="" className="absolute block inset-0 max-w-none size-full" src={imgAlertCircle} />
            </div>
          </div>
          <p className="[word-break:break-word] font-['Inter:Bold'] font-bold leading-[normal] min-w-full not-italic relative shrink-0 text-[#fafafa] text-[22px] text-center w-[min-content]" data-node-id="2:500">
            Focus Paused! You picked up your phone.
          </p>
          <p className="[word-break:break-word] font-['Inter:Regular'] font-normal leading-[1.45] not-italic relative shrink-0 text-[#71717a] text-[15px] text-center w-[315px]" data-node-id="2:501">
            Flip screen-down within 10s to keep your streak alive!
          </p>
          <div className="content-stretch flex flex-col gap-[8px] items-start overflow-clip relative shrink-0 w-full" data-node-id="2:502" data-name="Urgency cue">
            <div className="[word-break:break-word] content-stretch flex items-center justify-between leading-[normal] not-italic overflow-clip relative shrink-0 w-full whitespace-nowrap" data-node-id="2:503" data-name="Urgency labels">
              <p className="font-['Inter:Regular'] font-normal relative shrink-0 text-[#f59e0b] text-[11px] uppercase" data-node-id="2:504">
                Return phone
              </p>
              <p className="font-['Inter:Bold'] font-bold relative shrink-0 text-[#fafafa] text-[18px]" data-node-id="2:505">
                10s
              </p>
            </div>
            <div className="bg-[#1b1b1e] content-stretch flex flex-col h-[6px] items-start overflow-clip relative rounded-[999px] shrink-0 w-full" data-node-id="2:506" data-name="Countdown track">
              <div className="bg-[#f59e0b] h-[6px] relative rounded-[999px] shrink-0 w-[250px]" data-node-id="2:507" data-name="Time remaining" />
            </div>
          </div>
        </div>
        <div className="content-stretch flex flex-col gap-[20px] items-center overflow-clip relative shrink-0" data-node-id="2:508" data-name="Footer actions">
          <p className="[word-break:break-word] font-['Inter:Regular'] font-normal leading-[normal] not-italic relative shrink-0 text-[#71717a] text-[13px] whitespace-nowrap" data-node-id="2:509">
            Give up session
          </p>
          <div className="bg-[#fafafa] h-[5px] opacity-90 relative rounded-[999px] shrink-0 w-[134px]" data-node-id="2:510" data-name="Home indicator" />
        </div>
      </div>
    </div>
  );
}
