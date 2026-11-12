const imgSignal = "https://www.figma.com/api/mcp/asset/ed0328d2-7951-417d-9ecd-8d9f499766f8.svg";
const imgWifi = "https://www.figma.com/api/mcp/asset/845149f2-bacc-4c32-853b-7ae0705485bf.svg";
const imgIosBatteryFull = "https://www.figma.com/api/mcp/asset/38c9d7b5-47ca-45c7-b55e-2de6d53854e3.svg";
const imgRingTrack = "https://www.figma.com/api/mcp/asset/d194e925-a4ff-4345-9ce6-78a1b18b7335.svg";
const imgProgressRing = "https://www.figma.com/api/mcp/asset/27137699-3836-47e4-bea5-dbfd80daf246.svg";
const imgSmartphone = "https://www.figma.com/api/mcp/asset/e93444d5-aa33-4a1d-807c-e4e16ea929d4.svg";
const imgRotateCw = "https://www.figma.com/api/mcp/asset/c4d640ba-6379-4035-860c-3e6aefce00dc.svg";

export default function ReadyState() {
  return (
    <div className="bg-[#0b0b0c] border border-[#29292d] border-solid content-stretch flex flex-col items-start overflow-clip relative rounded-[44px] size-full" data-node-id="2:442" data-name="Ready state">
      <div className="content-stretch flex h-[48px] items-center justify-between overflow-clip px-[24px] relative shrink-0 w-full" data-node-id="2:443" data-name="Status bar">
        <p className="[word-break:break-word] font-['Inter:Regular'] font-normal leading-[normal] not-italic relative shrink-0 text-[#fafafa] text-[13px] whitespace-nowrap" data-node-id="2:444">
          9:41
        </p>
        <div className="content-stretch flex gap-[8px] items-center overflow-clip relative shrink-0" data-node-id="2:445" data-name="System status">
          <div className="h-[11px] relative shrink-0 w-[15px]" data-node-id="2:541" data-name="signal">
            <img alt="" className="absolute block inset-0 max-w-none size-full" src={imgSignal} />
          </div>
          <div className="h-[11px] relative shrink-0 w-[15px]" data-node-id="2:544" data-name="wifi">
            <img alt="" className="absolute block inset-0 max-w-none size-full" src={imgWifi} />
          </div>
          <div className="h-[10px] relative shrink-0 w-[20px]" data-node-id="2:547" data-name="ios-battery-full">
            <img alt="" className="absolute block inset-0 max-w-none size-full" src={imgIosBatteryFull} />
          </div>
        </div>
      </div>
      <div className="content-stretch flex h-[52px] items-center justify-between overflow-clip px-[24px] relative shrink-0 w-full" data-node-id="2:449" data-name="Header">
        <p className="[word-break:break-word] font-['Inter:Regular'] font-normal leading-[normal] not-italic relative shrink-0 text-[#fafafa] text-[20px] whitespace-nowrap" data-node-id="2:450">
          FlipFocus
        </p>
        <div className="bg-[#141416] border border-[#29292d] border-solid content-stretch flex items-start overflow-clip px-[12px] py-[8px] relative rounded-[999px] shrink-0" data-node-id="2:451" data-name="Streak">
          <p className="[word-break:break-word] font-['Inter:Regular'] font-normal leading-[normal] not-italic relative shrink-0 text-[#84cc16] text-[11px] whitespace-nowrap" data-node-id="2:452">
            🔥 4 Days
          </p>
        </div>
      </div>
      <div className="content-stretch flex flex-[1_0_0] flex-col items-center justify-between min-h-px overflow-clip pb-[14px] pt-[36px] px-[24px] relative w-full" data-node-id="2:453" data-name="Ready content">
        <div className="content-stretch flex flex-col gap-[32px] items-center relative shrink-0" data-node-id="2:454" data-name="Timer area">
          <div className="content-stretch flex flex-col items-center justify-center overflow-clip relative shrink-0 size-[286px]" data-node-id="2:455" data-name="Timer">
            <div className="-translate-x-1/2 -translate-y-1/2 absolute left-1/2 size-[286px] top-1/2" data-node-id="2:456" data-name="Ring track">
              <img alt="" className="absolute block inset-0 max-w-none size-full" src={imgRingTrack} />
            </div>
            <div className="-translate-x-1/2 -translate-y-1/2 absolute left-1/2 size-[286px] top-1/2" data-node-id="2:457" data-name="Progress ring">
              <img alt="" className="absolute block inset-0 max-w-none size-full" src={imgProgressRing} />
            </div>
            <div className="content-stretch flex flex-col items-center overflow-clip relative shrink-0" data-node-id="2:458" data-name="Countdown">
              <p className="[word-break:break-word] font-['Inter:Light'] font-light leading-[normal] not-italic relative shrink-0 text-[#fafafa] text-[58px] whitespace-nowrap" data-node-id="2:459">
                25:00
              </p>
            </div>
          </div>
          <div className="bg-[#141416] content-stretch flex gap-[4px] items-start p-[4px] relative rounded-[999px] shrink-0 w-full" data-node-id="2:460" data-name="Duration selector">
            <div className="bg-[rgba(0,0,0,0)] content-stretch flex flex-[1_0_0] h-[38px] items-center justify-center min-w-px overflow-clip relative rounded-[999px]" data-node-id="2:461" data-name="Duration option">
              <p className="[word-break:break-word] font-['Inter:Medium'] font-medium leading-[normal] not-italic relative shrink-0 text-[#71717a] text-[13px] whitespace-nowrap" data-node-id="2:462">
                15m
              </p>
            </div>
            <div className="bg-[#1b1b1e] content-stretch flex flex-[1_0_0] h-[38px] items-center justify-center min-w-px overflow-clip relative rounded-[999px] shadow-[0px_0px_30px_2px_rgba(16,185,129,0.22)]" data-node-id="2:463" data-name="Duration option">
              <p className="[word-break:break-word] font-['Inter:Bold'] font-bold leading-[normal] not-italic relative shrink-0 text-[#fafafa] text-[13px] whitespace-nowrap" data-node-id="2:464">
                25m
              </p>
            </div>
            <div className="bg-[rgba(0,0,0,0)] content-stretch flex flex-[1_0_0] h-[38px] items-center justify-center min-w-px overflow-clip relative rounded-[999px]" data-node-id="2:465" data-name="Duration option">
              <p className="[word-break:break-word] font-['Inter:Medium'] font-medium leading-[normal] not-italic relative shrink-0 text-[#71717a] text-[13px] whitespace-nowrap" data-node-id="2:466">
                45m
              </p>
            </div>
            <div className="bg-[rgba(0,0,0,0)] content-stretch flex flex-[1_0_0] h-[38px] items-center justify-center min-w-px overflow-clip relative rounded-[999px]" data-node-id="2:467" data-name="Duration option">
              <p className="[word-break:break-word] font-['Inter:Medium'] font-medium leading-[normal] not-italic relative shrink-0 text-[#71717a] text-[13px] whitespace-nowrap" data-node-id="2:468">
                Custom
              </p>
            </div>
          </div>
        </div>
        <div className="content-stretch flex flex-col gap-[16px] items-center overflow-clip relative shrink-0 w-full" data-node-id="2:469" data-name="Flip instruction">
          <div className="content-stretch flex gap-[16px] items-center overflow-clip relative shrink-0" data-node-id="2:470" data-name="Flip gesture">
            <div className="h-[58px] relative shrink-0 w-[40px]" data-node-id="2:550" data-name="smartphone">
              <img alt="" className="absolute block inset-0 max-w-none size-full" src={imgSmartphone} />
            </div>
            <div className="h-[24px] relative shrink-0 w-[38px]" data-node-id="2:553" data-name="rotate-cw">
              <img alt="" className="absolute block inset-0 max-w-none size-full" src={imgRotateCw} />
            </div>
            <div className="bg-[#1b1b1e] border-[#10b981] border-[1.5px] border-solid h-[58px] relative rounded-[8px] shrink-0 w-[40px]" data-node-id="2:473" data-name="Phone face down" />
          </div>
          <p className="[word-break:break-word] font-['Inter:Medium'] font-medium leading-[1.45] not-italic relative shrink-0 text-[#fafafa] text-[15px] text-center w-[300px]" data-node-id="2:474">
            Flip your phone screen-down on the desk to start focus
          </p>
          <p className="[word-break:break-word] font-['Inter:Regular'] font-normal leading-[normal] not-italic relative shrink-0 text-[#71717a] text-[11px] uppercase whitespace-nowrap" data-node-id="2:475">
            Face down · timer starts automatically
          </p>
        </div>
        <div className="bg-[#fafafa] h-[5px] opacity-90 relative rounded-[999px] shrink-0 w-[134px]" data-node-id="2:476" data-name="Home indicator" />
      </div>
    </div>
  );
}
