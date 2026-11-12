const imgCelebrationAura = "https://www.figma.com/api/mcp/asset/5a1416d8-3353-49d2-aaf7-6bfd7f845cc7.svg";
const imgSignal = "https://www.figma.com/api/mcp/asset/26045992-50ff-4803-91bb-1da2c7027d4f.svg";
const imgWifi = "https://www.figma.com/api/mcp/asset/13734a2d-1c85-4c74-af5a-c9fea37c7b8a.svg";
const imgIosBatteryFull = "https://www.figma.com/api/mcp/asset/2435da7c-7e53-4a1e-b402-0685bd4d8766.svg";
const imgCheckCircle = "https://www.figma.com/api/mcp/asset/0f7ea835-012d-46d3-89e8-7f890b1ace91.svg";

export default function CompletedState() {
  return (
    <div className="bg-[#0b0b0c] border border-[#29292d] border-solid content-stretch flex flex-col items-start overflow-clip relative rounded-[44px] size-full" data-node-id="2:512" data-name="Completed state">
      <div className="-translate-x-1/2 absolute left-1/2 size-[340px] top-[109px]" data-node-id="2:513" data-name="Celebration aura">
        <img alt="" className="absolute block inset-0 max-w-none size-full" src={imgCelebrationAura} />
      </div>
      <div className="content-stretch flex h-[48px] items-center justify-between overflow-clip px-[24px] relative shrink-0 w-full" data-node-id="2:514" data-name="Status bar">
        <p className="[word-break:break-word] font-['Inter:Regular'] font-normal leading-[normal] not-italic relative shrink-0 text-[#fafafa] text-[13px] whitespace-nowrap" data-node-id="2:515">
          9:41
        </p>
        <div className="content-stretch flex gap-[8px] items-center overflow-clip relative shrink-0" data-node-id="2:516" data-name="System status">
          <div className="h-[11px] relative shrink-0 w-[15px]" data-node-id="2:568" data-name="signal">
            <img alt="" className="absolute block inset-0 max-w-none size-full" src={imgSignal} />
          </div>
          <div className="h-[11px] relative shrink-0 w-[15px]" data-node-id="2:571" data-name="wifi">
            <img alt="" className="absolute block inset-0 max-w-none size-full" src={imgWifi} />
          </div>
          <div className="h-[10px] relative shrink-0 w-[20px]" data-node-id="2:574" data-name="ios-battery-full">
            <img alt="" className="absolute block inset-0 max-w-none size-full" src={imgIosBatteryFull} />
          </div>
        </div>
      </div>
      <div className="content-stretch flex flex-[1_0_0] flex-col items-center justify-between min-h-px overflow-clip pb-[14px] pt-[42px] px-[24px] relative w-full" data-node-id="2:520" data-name="Completion content">
        <div className="content-stretch flex flex-col gap-[24px] items-center relative shrink-0 w-full" data-node-id="2:521" data-name="Success moment">
          <div className="bg-[#141416] border-2 border-[#10b981] border-solid content-stretch flex flex-col items-center justify-center overflow-clip relative rounded-[999px] shadow-[0px_0px_30px_2px_rgba(16,185,129,0.22)] shrink-0 size-[132px]" data-node-id="2:522" data-name="Success seal">
            <div className="relative shrink-0 size-[54px]" data-node-id="2:577" data-name="check-circle">
              <img alt="" className="absolute block inset-0 max-w-none size-full" src={imgCheckCircle} />
            </div>
          </div>
          <div className="[word-break:break-word] content-stretch flex flex-col font-['Inter:Bold'] font-bold gap-[12px] items-center not-italic overflow-clip relative shrink-0 w-full" data-node-id="2:524" data-name="Celebration copy">
            <p className="leading-[normal] relative shrink-0 text-[#10b981] text-[11px] uppercase whitespace-nowrap" data-node-id="2:525">
              Focus complete
            </p>
            <p className="leading-[1.15] min-w-full relative shrink-0 text-[#fafafa] text-[30px] text-center w-[min-content]" data-node-id="2:526">
              25 Minutes of Deep Work Done!
            </p>
          </div>
        </div>
        <div className="bg-[#141416] border border-[#29292d] border-solid content-stretch flex flex-col gap-[16px] items-start overflow-clip p-[20px] relative rounded-[24px] shrink-0 w-full" data-node-id="2:527" data-name="Stats card">
          <div className="[word-break:break-word] content-stretch flex font-['Inter:Regular'] font-normal items-center justify-between leading-[normal] not-italic overflow-clip relative shrink-0 w-full whitespace-nowrap" data-node-id="2:528" data-name="Stat">
            <p className="relative shrink-0 text-[#71717a] text-[15px]" data-node-id="2:529">
              Total Today:
            </p>
            <p className="relative shrink-0 text-[#fafafa] text-[18px]" data-node-id="2:530">
              1h 40m
            </p>
          </div>
          <div className="bg-[#29292d] h-px relative shrink-0 w-full" data-node-id="2:531" data-name="Divider" />
          <div className="[word-break:break-word] content-stretch flex font-['Inter:Regular'] font-normal items-center justify-between leading-[normal] not-italic overflow-clip relative shrink-0 w-full whitespace-nowrap" data-node-id="2:532" data-name="Stat">
            <p className="relative shrink-0 text-[#71717a] text-[15px]" data-node-id="2:533">
              Streak Kept:
            </p>
            <p className="relative shrink-0 text-[#84cc16] text-[18px]" data-node-id="2:534">
              🔥 5 Days
            </p>
          </div>
        </div>
        <div className="content-stretch flex flex-col gap-[18px] items-center overflow-clip relative shrink-0 w-full" data-node-id="2:535" data-name="Completion actions">
          <div className="bg-[#10b981] content-stretch flex h-[58px] items-center justify-center overflow-clip relative rounded-[16px] shrink-0 w-full" data-node-id="2:536" data-name="Primary action">
            <p className="[word-break:break-word] font-['Inter:Bold'] font-bold leading-[normal] not-italic relative shrink-0 text-[#061711] text-[15px] whitespace-nowrap" data-node-id="2:537">
              Take a 5m Break
            </p>
          </div>
          <p className="[word-break:break-word] font-['Inter:Semi_Bold'] font-semibold leading-[normal] not-italic relative shrink-0 text-[#71717a] text-[15px] whitespace-nowrap" data-node-id="2:538">
            Done
          </p>
          <div className="bg-[#fafafa] h-[5px] opacity-90 relative rounded-[999px] shrink-0 w-[134px]" data-node-id="2:539" data-name="Home indicator" />
        </div>
      </div>
    </div>
  );
}
